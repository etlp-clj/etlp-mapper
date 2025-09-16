#!/usr/bin/env bash
set -euo pipefail

cd /workspace/etlp-mapper

lein deps
lein check
lein run :duct/migrator
lein test
lein test :integration
