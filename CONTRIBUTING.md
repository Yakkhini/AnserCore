# AnserCore Contributing Guide

AnserCore is at an early stage of development, so the contribution guidelines are intentionally simple. Please follow these essential rules:

1. Work in a fork, or, if contributors have write access, create a branch named dev/\<description> in this repository. Submit the changes through a pull request targeting main.
1. Follow the [AnserCore Coding Style Guide](https://yakkhini.github.io/AnserCore/develop/index.html).
1. Follow the commit convention described below.
1. Respect copyright and license requirements. Preserve existing notices and provide REUSE-compliant copyright and licensing information for new files.

Besides the above, a [Small, Focused Pull Requests](https://artsy.github.io/blog/2021/03/09/strategies-for-small-focused-pull-requests/) strategy is encouraged.

## Commit Convention Details

Every commit must begin with a subject line in the following format:

```
[<scope>] <summary>
```

The scope identifies the affected module, subsystem, or area of the repository. Use a single scope, or separate multiple scopes with a comma followed by a space (`, `).

Examples:

```text
[docs] clarify the contribution workflow
[build, ci] align compiler versions across environments
```

The entire subject line, including the scope, punctuation, and spaces, must not exceed 72 characters. 50 characters is recommended. Describe what changes and, where practical, the motivation.

## AI Policy

Every contribution and related development venues, including code, documentation, and communication on GitHub or other platforms, must have a responsible person in the loop who is accountable for that contribution and reviews it before submission.
