#!/bin/bash

# we want that all elm-stuff stay in src/main/elm
# whatever the path from which this script is called
ELM_DIR="$( cd "$( dirname "$0" )" && pwd )"
cd $ELM_DIR
elm make --optimize sources/api-authorizations.elm --output=generated/api-authorizations.js
elm make --optimize sources/user-api-token.elm --output=generated/user-api-token.js
