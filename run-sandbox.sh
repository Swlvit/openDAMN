#!/bin/bash
# Quick launcher with sandbox mode for safe first-run testing
cd "$(dirname "$0")"
./gradlew -q run --args="--sandbox"
