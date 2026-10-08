# Security

## Reporting a vulnerability

Please do not open a public issue. Send the details to LucasTHCR on [Discord](https://dc.gg/paperstream) or open a private security advisory on GitHub.

## Release signing

Every release jar comes with a `.sha512` checksum and a `.sig` file, an Ed25519 signature made with the PaperProxy release key. The private key never touches GitHub or Codeberg. The auto-updater installs a release only when the checksum matches and the signature is valid for one of the keys built into the running version (`ReleaseVerifier.RELEASE_KEYS`).

Current release key (X.509, Base64):

```
MCowBQYDK2VwAyEA+986KgDkYfb+7MASF4xQE65KPoLyoHnfTqzKeyhpp6o=
```

Next release key, already trusted since 1.2.2 and kept offline until it is needed:

```
MCowBQYDK2VwAyEAr15yRrW5oQhWuQ8yNd29b0i7OpEzF6CYVJxPHWjutFQ=
```

Since 1.2.2 the updater also checks that the version inside the downloaded jar matches the release tag and is newer than the running version, so an older signed jar cannot be installed under a newer tag.

Check a download by hand:

```bash
sha512sum -c paperproxy-<version>.jar.sha512
```

### Rotating the release key

Because the next key is already trusted, switching to it needs no transition release: sign with the next key, then ship a version that adds a new next key and drops the old one.

Without a prepared next key, a new key has to be introduced in two steps:

1. Release a version that lists the old and the new key in `RELEASE_KEYS`, still signed with the old key. Every proxy that updates to it now trusts both.
2. From the next release on, sign with the new key. Remove the old key from `RELEASE_KEYS` once enough servers run a version that knows the new one.

If the old private key was leaked, skip step 1: remove the old key at once and announce that servers have to update by hand once. A leaked key must never stay trusted.
