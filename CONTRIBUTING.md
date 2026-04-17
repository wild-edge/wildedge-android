# Contributing

## Development setup

1. Clone the repository:
   ```bash
   git clone https://github.com/wild-edge/wildedge-kotlin.git
   cd wildedge-kotlin
   ```

2. Install prerequisites:
   - JDK 21 (`brew install openjdk@21`)
   - Android SDK (via Android Studio or `sdkmanager`)
   - export JAVA_HOME=/opt/homebrew/opt/openjdk@21

3. Run tests:
   ```bash
    ./gradlew :wildedge:testDebugUnitTest
   ```

4. Run lint:
   ```bash
   ./gradlew :wildedge:lint
   ```

## Pull requests

1. Fork the repository and create a feature branch off `main`.
2. Make your changes and ensure tests and lint pass.
3. Submit a pull request with a clear description of the changes.

## Reporting issues

- Use GitHub Issues for bugs and feature requests.
- Include steps to reproduce, SDK version, device/emulator details, and relevant logs.

## License

This project is licensed under the [Business Source License 1.1](LICENSE). By contributing, you agree that your contributions will be licensed under the same terms.
