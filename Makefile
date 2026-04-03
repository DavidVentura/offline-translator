.PHONY: lint lint-fix check build
PODMAN=
lint:
	git grep println app/src/main/ && echo "Found println" && exit 1 || true
	./gradlew ktlintCheck
lint-fix:
	./gradlew ktlintFormat
build:
	./build.sh --podman "$(PODMAN)"
check:
	./gradlew detekt
