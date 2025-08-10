# GitHub Actions Workflows

This document describes the GitHub Actions workflows that have been added to provide continuous integration and automation for this project.

## Workflows Overview

### 1. CI (Continuous Integration) - `ci.yml`
**Triggers:** Push and PR to master/main/test branches
**Purpose:** Core build and test workflow
- Builds the project with Maven
- Runs tests (if any exist)
- Creates JAR artifacts
- Validates compilation across Java versions

### 2. Build Validation - `build-validation.yml`
**Triggers:** Every push, PR, and manual dispatch
**Purpose:** Fast validation checks
- Validates repository structure
- Checks file encoding
- Validates plugin.yml format
- Provides project statistics
- Lightweight checks that run quickly

### 3. Security Scan - `security.yml`
**Triggers:** Push/PR to main branches + weekly schedule
**Purpose:** Security vulnerability detection
- OWASP Dependency Check for known vulnerabilities
- Trivy filesystem scanning
- Uploads results to GitHub Security tab
- Weekly scheduled scans

### 4. Code Quality - `code-quality.yml`
**Triggers:** Push and PR to main branches
**Purpose:** Code quality and style enforcement
- Spotless formatting checks
- SpotBugs static analysis
- Checkstyle validation
- Custom code issue detection
- POM structure validation

### 5. Dependency Updates - `dependency-updates.yml`
**Triggers:** Weekly schedule + manual dispatch
**Purpose:** Dependency maintenance
- Checks for outdated dependencies
- Generates dependency trees
- Maven plugin update detection
- Creates dependency reports

### 6. Maven Package - `maven-publish.yml` (Existing)
**Triggers:** GitHub releases
**Purpose:** Artifact publishing
- Builds and publishes JAR to GitHub Packages
- Only runs on release creation

## Workflow Run Frequency

With these workflows, the repository now has:
- **High frequency:** Build validation on every push/PR
- **Medium frequency:** CI, security, and code quality on push/PR to main branches
- **Low frequency:** Dependency updates weekly, security scans weekly
- **Event-driven:** Maven package on releases

## Benefits

1. **Continuous Integration:** Every change is automatically built and tested
2. **Security:** Regular vulnerability scanning and security monitoring
3. **Code Quality:** Automated code style and quality checks
4. **Maintenance:** Regular dependency update monitoring
5. **Visibility:** Build status badges show project health
6. **Artifacts:** Build outputs are preserved for debugging and distribution

## Monitoring

- Check the Actions tab in GitHub to see workflow runs
- Build status badges in README show current status
- Security findings appear in the Security tab
- Artifacts are available for download from successful runs

## Next Steps

Consider adding:
- Automated dependency update PRs with Dependabot
- Release automation workflows
- Performance testing workflows
- Integration tests with test Minecraft servers