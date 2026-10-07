# Kubernetes-Self-Healing-Agents
## Local dev
To run the project locally:
1. Start Postgres: `docker-compose up -d`
2. Create kind cluster: `kind create cluster --config kind-config.yaml`
3. Run the application: `./gradlew bootRun`

