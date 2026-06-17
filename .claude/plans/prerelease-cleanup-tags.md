# Plan: Delete old prerelease tags when releasing a stable version

## Problem
The current workflow deletes old prerelease **GitHub Releases** when a stable (non-prerelease) tag is pushed, but it leaves the underlying **Git tags** behind. Over time this litters the repository with orphaned alpha/beta/rc tags.

## Location
`app/src/main/java/com/kirin/mt/.github/workflows/android-build.yml`, step "Delete old prereleases".

## Change
Extend the existing Python script in that step so that, after deleting a prerelease with `gh release delete <tag> --yes`, it also deletes the corresponding Git tag with `git push origin --delete <tag>`.

The step already has `permissions: contents: write`, which is sufficient to push tag deletions.

## Concern: remote refs in actions/checkout
`actions/checkout@v4` by default fetches only the tag being pushed (`fetch-depth: 1` + refspec for the triggering tag). To delete a remote tag via `git push`, we need the remote URL to be present. The checkout action configures `origin` already, so a `git push origin --delete <tag>` should work as long as `GH_TOKEN` (or `GITHUB_TOKEN`) is used for authentication.

Alternatively, use `gh api` / `gh git` or a raw `git` command with the remote URL and token. The simplest is to use `git push origin --delete <tag>` after the checkout, relying on the action's embedded token.

## Implementation
Replace the loop body:
```python
print(f'Deleting old prerelease {tag} (versionCode {vc})')
subprocess.run(['gh', 'release', 'delete', tag, '--yes'], check=False)
subprocess.run(['git', 'push', 'origin', '--delete', tag], check=False)
```

## Out of scope
- No change to when cleanup happens: still only on stable tag pushes.
- No change to the version-code comparison logic.
