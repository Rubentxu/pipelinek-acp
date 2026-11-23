package pipelinek.acp

import com.agentclientprotocol.model.SessionUpdate
import dev.rubentxu.pipeline.v2.events.registry.EmissionOutcome
import dev.rubentxu.pipeline.v2.events.registry.EventDefinition
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionCreation
import dev.rubentxu.pipeline.v2.events.registry.EventDefinitionContributor
import dev.rubentxu.pipeline.v2.events.registry.EventPayloadCodec
import dev.rubentxu.pipeline.v2.events.registry.PayloadDecode
import dev.rubentxu.pipeline.v2.events.registry.PluginEventEmission
import dev.rubentxu.pipeline.v2.events.registry.PLUGIN_EVENT_EMISSION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.step.StepHandlerContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The codec of this plugin's observations.
 *
 * ## What the SDK asks of a codec, and what this one does
 *
 * The published contract says the codec must be pure and deterministic — the same payload
 * encodes to the same string, every time — and that a payload written under a version the
 * codec does not know MUST be refused with [PayloadDecode.UnknownSchemaVersion] rather than
 * read as "probably fine".
 *
 * So the version is checked BEFORE the text is parsed: a payload of a newer shape is refused
 * without being read at all, because parsing it under the old rules is exactly the silent
 * semantic drop the contract forbids. A payload of the known version that does not conform is
 * [PayloadDecode.Malformed], never a partially decoded fact.
 *
 * `ignoreUnknownKeys = false` for the same reason: a field this version does not know means
 * the payload was written by something else, and reading it as if it were ours would invent
 * facts. `encodeDefaults = true` and a fixed field order make the encoding deterministic.
 */
internal object AcpObservationCodec : EventPayloadCodec<AcpObservation> {

    /** The schema version of the payload shape this codec reads and writes. */
    const val SCHEMA_VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    override fun encode(payload: AcpObservation): String =
        json.encodeToString(AcpObservation.serializer(), payload)

    override fun decode(raw: String, schemaVersion: Int): PayloadDecode<AcpObservation> {
        if (schemaVersion != SCHEMA_VERSION) {
            return PayloadDecode.UnknownSchemaVersion(schemaVersion)
        }
        return try {
            PayloadDecode.Decoded(json.decodeFromString(AcpObservation.serializer(), raw))
        } catch (e: SerializationException) {
            PayloadDecode.Malformed(
                e.message ?: "the payload is not an observation of this plugin",
            )
        }
    }
}

/**
 * The event kinds this plugin contributes.
 *
 * One kind per fact token, and the tokens come from the matrix — so the kinds this plugin
 * declares and the facts it publishes are the same list, read from one place. The manifest
 * reads the same list, which is what makes the host's cross-check of "what the manifest
 * declares" against "what the plugin contributes" a comparison of a thing with itself.
 */
class AcpObservationContributor : EventDefinitionContributor {

    override val id: String = AcpPluginDeclaration.CONTRIBUTOR_ID

    override fun definitions(): Iterable<EventDefinitionCreation<*>> =
        AcpSchemaMatrix.kinds.map { kind ->
            EventDefinition.create(
                kind = kind,
                schemaVersion = AcpObservationCodec.SCHEMA_VERSION,
                payloadClass = AcpObservation::class.java,
                codec = AcpObservationCodec,
                emittedBy = AcpPluginDeclaration.CONTRIBUTOR_ID,
            )
        }
}

/**
 * Where the agent's session updates go once they are observed.
 *
 * The implementation MUST NOT block: it runs on the thread that reads the agent's pipe, and
 * that thread is the one that has to stay free to deliver the cancellation.
 *
 * The lifecycle doors carry the facts the plugin establishes ABOUT ITS OWN EXCHANGE — the one
 * it opened, the turn that ended, the cancel it said, the refusal it answered. They default to
 * silence because a sink that only projects updates is still a valid observer; a run that owes
 * the lifecycle facts to its stream wires the observing publisher, and there each one lands as
 * a VERIFIED fact of the same vocabulary.
 */
interface AcpUpdateSink {
    /** One session update the agent sent. */
    fun accept(update: SessionUpdate)

    /** The plugin opened this exchange. */
    fun exchangeStarted() {}

    /** One prompt turn ended with the stop reason the agent gave. */
    fun turnEnded(turnIndex: Int, stopReason: String) {}

    /** The exchange was cancelled before it finished; [reason] names whose cancellation it was. */
    fun exchangeCancelled(reason: String) {}

    /** The exchange ended in a refusal; [failureKind] is the typed refusal's classification. */
    fun exchangeRefused(failureKind: String) {}
}

/**
 * The publisher: what the plugin observed, on its way into the run's event stream.
 *
 * It has three jobs and no others:
 *
 * - **normalize**: every update goes through the decoder, so what leaves this class is the
 *   plugin's own vocabulary and never an SDK class;
 * - **reduce**: the facts that are worth publishing are the ones the reducer decides, and it
 *   decides them by merging what it already knows — a partial update never erases a fact, and
 *   a completion is published once;
 * - **publish**: each fact is emitted through the published seam, under the kind of its
 *   token, so a consumer that does not link this plugin's codec can still read the stream.
 *
 * The publishing happens on the publisher's OWN coroutine, never on the reader's: the reader
 * only offers facts to the lane, and the lane never makes it wait.
 */
internal class AcpObservationPublisher(
    private val emission: PluginEventEmission,
    /**
     * The identity of the exchange the facts belong to.
     *
     * It is read per fact because the exchange is opened in the middle of the session: the
     * facts said before it existed still belong to it, and the facts said after it carry the
     * identity the exchange minted.
     */
    private val exchangeRef: () -> String,
) : AcpUpdateSink {

    private val lane = AcpObservationLane(AcpObservationCodec)
    private val reducer = AcpObservationReducer(exchangeRef)

    /**
     * The fold is confined by serialization: [accept] runs on the thread of the prompt flow
     * AND on the thread of the transport (reverse notifications arrive there), and the
     * reducer's merged view is a law only if two folds never interleave. Only the fold takes
     * this lock — the lane offer and the wake-up stay outside it, so a reader still never
     * waits on publishing.
     */
    private val foldLock = Any()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var unpublished: Int = 0
    private var published: Int = 0

    /** How many facts reached the stream. */
    val publishedCount: Int get() = published

    /** The reader's side: never blocks. */
    override fun accept(update: SessionUpdate) {
        val observation = AcpObservationDecoder.decode(update, exchangeRef())
        for (fact in fold(observation)) {
            lane.offer(fact)
        }
        wakeup.trySend(Unit)
    }

    /** Folds [observation] and offers the resulting facts to the lane; the lifecycle door. */
    private fun publishFact(fact: AcpObservation) {
        for (merged in fold(fact)) {
            lane.offer(merged)
        }
        wakeup.trySend(Unit)
    }

    override fun exchangeStarted() =
        publishFact(AcpObservation.ExchangeStarted(exchangeRef(), AcpEvidence.VERIFIED))

    override fun turnEnded(turnIndex: Int, stopReason: String) =
        publishFact(AcpObservation.TurnEnded(exchangeRef(), AcpEvidence.VERIFIED, turnIndex, stopReason))

    override fun exchangeCancelled(reason: String) =
        publishFact(AcpObservation.ExchangeCancelled(exchangeRef(), AcpEvidence.VERIFIED, reason))

    override fun exchangeRefused(failureKind: String) =
        publishFact(AcpObservation.ExchangeRefused(exchangeRef(), AcpEvidence.VERIFIED, failureKind))

    private fun fold(observation: AcpObservation): List<AcpObservation> =
        synchronized(foldLock) { reducer.apply(observation) }

    /**
     * Publishes what the lane holds, until the lane is empty.
     *
     * Called on the publisher's coroutine. It is the only place that touches the emitter, and
     * a fact the emitter refuses is COUNTED: a seam that is not there is a fact about the run,
     * not a no-op.
     */
    fun drain() {
        while (true) {
            val entry = lane.poll() ?: return
            val outcome = emission.emit(AcpFactToken.kindOf(entry.first.token), entry.first)
            if (outcome is EmissionOutcome.UnregisteredKind) {
                unpublished++
            } else {
                published++
            }
        }
    }

    /**
     * The publisher's loop: publish what arrives, until the stream is closed.
     *
     * The final drain and the gap accounting happen whatever ends the loop — a completed
     * exchange, a cancellation or a failure — because a fact that is lost without anyone
     * saying so is the failure this whole mechanism exists to avoid.
     */
    suspend fun run() {
        try {
            for (signal in wakeup) {
                drain()
            }
        } finally {
            drain()
            publishGap()
        }
    }

    /** Closes the stream of wake-ups; the publisher's loop ends and drains what is left. */
    fun close() {
        wakeup.close()
    }

    /** Publishes what the lane lost, if anything was lost. */
    fun publishGap() {
        val accounting = lane.accounting(exchangeRef(), unpublished)
        if (accounting.lost == 0 && accounting.coalesced == 0 && accounting.unpublished == 0) {
            return
        }
        emission.emit(AcpFactToken.kindOf(AcpFactToken.OBSERVATION_GAP), accounting)
    }

    companion object {
        /**
         * The identity of the exchange one step invocation runs.
         *
         * It is the run and the position inside it, not the agent's session id: the session id
         * is the agent's word, while which exchange a fact belongs to is a fact the plugin
         * establishes itself. Two turns of a composed session are two invocations and two
         * identities, which is what lets a reader tell whose chunk it is reading when a run
         * opens more than one exchange.
         */
        fun exchangeRefOf(context: StepHandlerContext): String =
            "run-${context.runId}-${context.stepIndex}"
    }
}

/**
 * Runs [block] with an observation publisher live, and leaves nothing of the publisher behind.
 *
 * The publisher's loop runs on the caller's scope, so the reader thread of the ACP pipe never
 * pays for an append to the event store, and the close/join at the end is what guarantees that
 * the last facts of the exchange and the gap accounting reach the stream BEFORE the step's
 * output is journaled. A publisher left running past the step would publish facts into a run
 * the engine already closed.
 */
internal suspend fun <T> withObservationPublisher(
    emission: PluginEventEmission,
    exchangeRef: String,
    block: suspend (AcpObservationPublisher) -> T,
): T = kotlinx.coroutines.coroutineScope {
    val publisher = AcpObservationPublisher(emission) { exchangeRef }
    val loop = launch(start = CoroutineStart.UNDISPATCHED) { publisher.run() }
    try {
        block(publisher)
    } finally {
        publisher.close()
        loop.join()
    }
}
