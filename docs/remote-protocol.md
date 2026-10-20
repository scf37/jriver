# Remote protocol

How jriver keeps a backup on a storage that offers nothing but `download`, `upload`, `delete` and `list`.

## Goals

- No atomic operations required from storage: `upload` may be non-atomic, may be interrupted at any time, and its
  overwrite behaviour is unspecified.
- No remote locks. An aborted or parallel backup must never lose or corrupt committed data; the price is junk files
  that are cleaned up later. (A local per-url lock on the machine running river prevents the common mistake of
  starting the same backup twice. It is not part of the correctness argument.)
- Concurrent backups of one url are a configuration mistake, not a supported mode. They must not corrupt the
  archive, but one of them may end up not being part of the history.

Threat model: whoever can write to the storage can restore an older state at any time, so rollback is not guarded
against. What the seal protects (encrypted backups) is the content of the config: settings, and which files make up
the archive.

## Storage API

Four operations over names of the form `name` or `folder/name`, implemented by the driver scripts
`drivers/<name>/{download,upload,delete,list}`. Every operation reports failure with a **non-zero exit code**; the
output of a failed operation must not be trusted.

| operation         | semantics |
|-------------------|-----------|
| `download <name>` | write the file to stdout. Non-zero exit on any failure or short read. |
| `upload <name>`   | read the file from stdin. Non-atomic: a partial object may be visible while it runs or after it fails. Exit 0 **only** if the whole input was stored completely and durably. |
| `delete <name>`   | idempotent; a missing file is exit 0. A folder that this leaves empty is removed too (where folders exist). |
| `list <prefix>`   | the entries (files and folders) of one level whose name starts with prefix, one per line, as `name` or `folder/name`; a prefix with a folder part lists inside that folder. Exit 0 with empty output means "no entries"; a failed or partial listing must be non-zero. |

Required consistency: once `upload` has returned 0 the object is fully readable by `download` and present in `list`
(read-after-write, across objects). The commit step below relies on this. A client never uploads a name again after
a failure; it uses a fresh random part.

## Names

```
config.<revision>.<random>.yaml                  commit marker of a revision (several if backups raced)
<full backup>/arc<revision>.<random>.jpaq        a tome, written by that revision
<full backup>/idx<revision>.<random>.jpaq        the index of the full backup, written by that revision
```

A full backup is a folder named by its creation time, `yyyyMMddTHHmmssSSS` (UTC), for example `20261020T113453047`.

`<revision>` is zero padded to 5 digits (compared numerically), `<random>` is 32 bits as 8 lowercase hex digits.
The random part only has to separate writers that target the **same** revision, so 32 bits is plenty.

jriver accepts only names of exactly these formats from a listing and from a config; anything else is ignored,
never passed to a driver and never deleted (so the directory can hold foreign files). The client does not tell files
from folders in a listing; an entry is a folder if its name (with or without a trailing slash) is a timestamp of the
format below, and nothing else is ever looked at. The names of the files and folders it creates cannot clash. A tome or index named in a
config must be in the folder of its full backup, and the folder name must be a timestamp of that format.

## Revisions, full backups

The **revision** is a counter of committed changes: a backup run that stored something, or a change of the config
(`river config`). Every file carries the revision that wrote it. Revision 1 is the config written by `create`.

A backup consists of **full backups**: a full version plus the incremental versions after it, each full backup being
one jpaq sequential archive (tomes + one index) in a folder of its own. The config says which files make up each. jpaq works with logical
names (`arc-000003.jpaq`, `index`); the storage adapter (`DriverStorage`) maps them using the config: the k-th
logical tome is `tomes[k-1]`, `index` is the full backup's `index`, and what a run writes gets new names for the
revision being committed. It refuses to write a tome that does not continue the tomes of the config, and it never
touches a committed file. Archives are not repacked or compacted.

## Config file

YAML. The settings of the backup (`paths`, `exclude`, `method`, retention, `encrypted`) and the state: `revision`,
and per full backup `name` (= its folder), `index`, `tomes` (in archive order) and `versions`. The revision inside must equal the one
in the file name, otherwise the config is invalid (no renaming/replaying of old configs). The last line is the seal:

```
<yaml body>
mac: <salt>.<hash>
```

The file ends with `\nmac: <salt hex>.<64 hex chars>\n`. The hash covers every byte up to and including the `\n` in
front of `mac:`.

- **Encrypted backup:** `salt` is 16 random bytes (32 hex chars), fresh for every config.
  `key = scrypt(river_key as UTF-8, salt, N=131072, r=8, p=1)` (128 MiB, about 0.4 s), `hash = HMAC-SHA256(key,
  covered bytes)`. These are constants of jriver (never read from a file), and the same cost as the key derivation
  of the jpaq sequential format (`Crypto.stretchKeySequential`); the zpaq classic format keeps N=16384 for
  compatibility. The seal is therefore never a cheaper password-guessing oracle than the archive itself. The mode
  follows from the caller having a key, never from the file (no downgrade to the plain form).
- **Unencrypted backup:** `salt` is empty (`mac: .<hash>`) and `hash = SHA-256(covered bytes)`. It only detects
  corruption; an unencrypted backup is not protected against tampering.

A config is **well-formed** when the file ends with a complete `mac:` line as above. `upload` stores a prefix of the
stream and the body never contains a `mac:` line, so a well-formed config is a completely written one: by
construction all files it references were completely stored before it (see Backup). A config that is not
well-formed is a torn upload and is ignored everywhere. A well-formed config is **valid** if its seal verifies and
its revision matches its name. The seal is checked on the raw bytes before the YAML is parsed.

## Latest config

`L` = the highest revision that has at least one well-formed config. Verify the well-formed configs of revision `L`;
any valid one may be used. There is no notion of a winner. All valid configs at `L` are legitimate archives. After
concurrent backups they may differ in contents (for example snapshots of different dates); that is acceptable.
Configs of `L` that fail verification are ignored as long as at least one verifies.

If the well-formed configs of `L` exist and **none** verifies, that is an error: wrong key or missing key (corruption
for an unencrypted backup). Never fall back to an older revision, never treat it as "no backup", never start a new
backup over it and never clean up. A complete config planted at a high revision therefore blocks the backup until
someone removes it; the message names the revision.

If no well-formed config exists at all, the backup does not exist. Torn configs, including those of a `create` that
never finished, do not count.

Cost: one scrypt run per examined config (salts differ), cached per salt for the process. Normally that is one or
two configs (`L`, and `L-1` for cleanup).

## Backup

1. `list config.`; failure aborts. Pick the latest config `C` (revision `L`; none means there is no backup).
2. Run the archiver on the full backup to extend (or a new one): it reads the old index named by `C` and streams the
   new tome and the new index with `upload`, under new names for revision `L+1`. Each `upload` must exit 0. Any
   failure aborts the run: the junk stays and is ignored. A run that changes nothing writes nothing and commits
   nothing.
3. Compose the config for revision `L+1` from `C` with the new files, drop full backups beyond
   `keep_full_backup_count` (they are simply no longer referenced; their files and folders go with the next cleanup), seal it and `upload`
   `config.<L+1>.<random>.yaml`. Before that, `list config.` again and refuse to commit if a well-formed config of
   revision `L+1` or later appeared. When this `upload` returns 0 the backup is committed.
4. Cleanup (below). A failed cleanup does not fail the backup.

`river config` is the same with no new files; `river create` writes revision 1 if no well-formed config exists.

## Restore, list, verify

Pick the latest config, `download` the files it references. If that fails and a newer revision exists by now (cleanup
may have removed the files of the one we used), start again from the new latest config. Files not referenced by the
chosen config are never read.

## Cleanup

Let `L` be the revision just committed. Read **all valid configs at `L` and at `L-1`**. The *keep set* is the union
of those config files and every index and tome they reference.

Delete every file whose revision is **below `L`** and that is not in the keep set. Files with revision `L` or higher
are never touched.

Why this is safe:

- A file with revision `>= L` may belong to a backup that is still running (targeting `L` or later) and has not
  committed yet. Deleting it would let that backup commit later a valid config with missing files, which a later
  backup could pick as its base. So it is left alone; the next revision's cleanup collects it if it turns out to
  be junk.
- A backup that targets a revision `< L` and commits late is irrelevant: configs below `L` are never selected as a
  base, and cleanup only removes unreferenced files, so nothing of `L` and `L-1` is affected.
- A cleaner working from a stale view sees a lower `L`; since `L` only grows, it can only delete less.
- Two configs at `L` (a race) are both read, so the files of both are kept. Whichever one the next backup builds
  on, its files are in the keep set; the other branch is dropped two revisions later.

Rules:

- Delete in this order: configs first, then indexes, then tomes, so a valid config never refers to a missing file.
- Delete nothing if a file referenced by the config(s) at `L` or `L-1` is missing from the listing, or if anything
  cannot be read. The listing is the root, then every full backup folder in it (1 + N calls).
- The last two revisions are always kept as a fallback (the config and the index of `L-1`), and a restore that is
  still running on `L-1` is unaffected.
- Cleanup may run concurrently with backups and with other cleanups.

`river delete` removes every file of a recognized name: indexes and tomes first, then the configs from the oldest to
the newest, so that an interrupted delete can be repeated.

## Failure scenarios

| scenario | result |
|----------|--------|
| backup killed during tome/index upload | junk files, no config; ignored; deleted by cleanup after the next revision |
| killed during config upload | torn config, ignored; same cleanup |
| `upload` reports success but is incomplete | excluded by the API contract (exit code) |
| two backups on the same revision | two valid configs at `L+1`; either may be the base later; cleanup keeps both until superseded; the one that sees the other's commit first refuses to commit |
| slow backup commits an old revision | never selected, its files are removed by cleanup |
| restore while a backup + cleanup run | restore keeps working for two revisions; on failure it restarts from the new latest config |
| `list` fails or returns garbage | hard error; no backup, no cleanup, no `create` |
| well-formed configs at the latest revision, none verifies | hard error (wrong or missing key), nothing is touched |

## Known limitations

- Rollback (someone with write access deletes the newest configs, leaving an older genuine one) is not detected;
  that would need client-side state. Not planned.
- Data files are authenticated by the archiver's own encryption and checksums only, not by the config seal.
- With encryption every tome and index opened by the archiver costs one scrypt run of N=131072.
