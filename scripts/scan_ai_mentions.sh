#!/usr/bin/env bash
set -euo pipefail

# Scan the repo for potential AI-attribution wording in comments or docs.
# Excludes build outputs and dependency caches.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Patterns to search (case-insensitive). Expand carefully to avoid over-flagging.
PATTERN='(?i)(chat\s*gpt|gpt-?3|gpt-?4|openai|copilot|claude|anthropic|bard|gemini|mistral|llama|deepseek|perplexity|\bAI language model\b|\bLLM\b|como\s+IA|como\s+una\s+IA|soy\s+una\s+IA|asistente\s+de\s+IA|inteligencia\s+artificial|generad[oa]\s+por\s+IA|hech[oa]\s+por\s+IA|cread[oa]\s+por\s+IA|con\s+ayuda\s+de\s+IA|generated\s+by\s+(AI|ChatGPT|Copilot)|as\s+an\s+AI\b)'

# Exclusions
EXCLUDE_DIRS=(
  ".git" ".gradle" ".idea" "build" "app/build" "functions/lib" "functions/node_modules" "node_modules" "app/.cxx"
)

# Build the --exclude-dir args
EXCLUDE_ARGS=()
for d in "${EXCLUDE_DIRS[@]}"; do
  EXCLUDE_ARGS+=("--exclude-dir=${d}")
done

# Run the scan.
if grep -RInI ${EXCLUDE_ARGS[@]} -E "$PATTERN" . ; then
  echo
  echo "Potential AI-attribution wording found above. Please review and neutralize those mentions."
  exit 1
else
  echo "No AI-attribution wording found in tracked source/docs."
fi

