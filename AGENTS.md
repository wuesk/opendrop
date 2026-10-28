# Repository Guidelines

## Project Structure & Module Organization

This workspace currently has no application source, tests, assets, or project manifest. The existing hidden directories (`.agents/`, `.codex/`, `.aws/`, and `.git/`) are reserved workspace metadata; do not use them for application code.

When introducing code, group it by responsibility. Suggested locations are `src/` for implementation, `tests/` for automated tests, `assets/` for static resources, and `docs/` for supporting documentation. Create only directories the project needs, and document their purpose in `README.md`.

## Build, Test, and Development Commands

No build system, dependency manager, development server, or test command is configured. Do not assume commands such as `npm test` or `make build` are available.

When adding the first implementation, document exact dependency installation, local execution, build, and test commands in `README.md`. Prefer reproducible scripts over undocumented manual steps, and commit the appropriate dependency lockfile.

## Coding Style & Naming Conventions

No language or formatter has been established. Follow the selected language’s standard conventions and configure its formatter and linter alongside the first code contribution. Use consistent indentation within each file, descriptive names, and modules focused on one responsibility. Avoid introducing competing formatting tools.

## Testing Guidelines

No testing framework or coverage threshold is configured. Introduce a framework appropriate to the chosen stack and document how to run it. Name tests after the behavior they verify, following the framework’s discovery conventions. Cover meaningful behavior, failure cases, and bug regressions.

## Commit & Pull Request Guidelines

Git history is unavailable in this workspace, so existing commit conventions cannot be verified. Use concise, imperative subjects, such as `Add project setup documentation`, and keep each commit focused.

Pull requests should explain the change, relevant issue links, and validation performed. Include screenshots for visual changes. Explicitly identify checks that could not be run and why.

## Security & Configuration

Never commit credentials, access tokens, or private configuration. Document required environment variables using placeholders, and exclude local secrets and generated output from version control.
