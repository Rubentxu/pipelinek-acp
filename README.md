# pipelinek-acp

Reactive Agentic Pipelines for [PipelineK](https://github.com/Rubentxu/pipeline-kotlin): an ACP-client plugin that lets a Kotlin pipeline drive an external agent over the Agent Client Protocol, react to typed agent events, and expose its own steps as MCP tools.

- Backlog (autoridad única, living in [pipelinek-home](https://github.com/Rubentxu/pipelinek-home)): filter tasks by label `repo:pipelinek-acp`; SDK contracts carry `repo:pipeline-kotlin`.
- Status: the plugin ships five steps — `acpPrompt` (one delegation), `acpTask` (an isolated delegation under a profile), `acpSession` (a composed session: several sequential turns plus the negotiated session operations), `acpReactive` (one delegation that reacts to the agent's updates by dispatching its body's reaction slot, once per update, each with its own durable identity) and `acpReaction` (the body child it dispatches). The intake tasks are on the home board; the work is tracked there.
- Human interaction: the steps that can be asked by the agent for a person's input declare the human-decision capability and put the question to a person through the port the published SDK carries; the person's answer reaches the agent and grants nothing — a permission is still the declared authority's to decide.

CI is PipelineK running locally (`pipeline.kts`), not GitHub Actions.
