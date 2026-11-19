#!/usr/bin/env bash
# Cheap and mechanical, and it can fail: admission refuses a jar whose manifest is missing or
# whose SPI registration does not name the contributor.
set -eu
cd "$(dirname "$0")/.."
JAR=build/libs/pipelinek-acp-0.1.0-operator.jar
test -f "$JAR"
unzip -p "$JAR" META-INF/services/dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor \
  | grep -qx 'pipelinek.acp.AcpContributor'
unzip -p "$JAR" META-INF/pipelinek/plugin-manifest.json \
  | grep -q '"pipelinek.acp.prompt"'
unzip -p "$JAR" META-INF/pipelinek/plugin-manifest.json \
  | grep -q '"pipelinek.acp.task"'
