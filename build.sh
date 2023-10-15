docker buildx build --builder hydrobuild --platform linux/amd64,linux/arm64 --tag vonwig/lsp:json-rpc --file Dockerfile --push .
