# Changelog

All notable changes to this project are documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `POST /api/v1/answers` answers a technical question with OpenAI using a strict JSON Schema for the reply (`answer`, `confidence`, `sources`).
- Validation and model failures return RFC 9457 Problem Details (`400` and `502`).
- Health endpoint at `/actuator/health`.
- The server listens only on `127.0.0.1` by default (`SERVER_ADDRESS` overrides it).
- The application refuses to start when `OPENAI_API_KEY` is not set, instead of answering every question with `502`.
