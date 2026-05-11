# Contributing

Thanks for your interest in contributing.

## Reporting bugs

Open an issue describing the problem, steps to reproduce, and expected behaviour.

## Proposing changes

1. Fork the repository.
2. Create a branch: `git checkout --branch my-fix`.
3. Make your changes and commit with a clear message.
4. Open a pull request against `main`.

## Code style

This project uses [prettier-java](https://github.com/jhipster/prettier-java) for formatting. Before opening a PR, run:

```bash
mvn prettier:write
```

All unit and integration tests must pass:

```bash
mvn verify
mvn verify --activate-profiles integration-test
```

## No CLA required

You do not need to sign a Contributor License Agreement. By submitting a pull request, you agree to license your contribution under the repository's existing [LICENSE](./LICENSE).
