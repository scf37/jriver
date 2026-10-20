# jriver

Incremental remote backups with no local temporary space. Java port of [river](https://github.com/scf37/river), built on
[jpaq](https://github.com/scf37/jpaq) (zpaq in Java with a streaming archive format) instead of the zpaq binary:
archives are streamed straight through four shell scripts, both when backing up and when restoring.

- **self-discovery**: `river help`, `river init` writes a commented config; everything else is a few commands
- **self-containment**: the config lives next to the backup (`config.*.yaml`). To restore you need river, the backup url (and the key if encrypted)
- **easy extension**: a driver is four scripts (upload, download, delete, list) that move bytes over stdin/stdout; no atomic operations needed from the storage
- **unix way**: no scheduler (use cron), no own transport, encryption or compression (zpaq's), silent unless something is wrong
- **simple archive**: tomes and an index of the jpaq sequential format, one tome per backup run; readable with the jpaq command line

## Build

JDK 25 and Maven 3.9+. jpaq is not on Maven Central yet, so install it first:

```
git clone https://github.com/scf37/jpaq && (cd jpaq && mvn install -DskipTests)
mvn package          # runs the tests, builds target/river.jar
./dist.sh            # jlink distribution in target/dist/jriver
target/dist/jriver/river help
```

The distribution is self-contained (no Java needed on the target machine, same OS/arch as the build):

```
river       launcher script (a shell script on purpose: the launchers java/jlink generate are buggy), runs bin/java -m io.github.scf37.river
bin/ lib/   minimal Java runtime made by jlink; the application is the module io.github.scf37.river inside lib/modules
drivers/    drivers/<name>, found next to the launcher
```

Copy or unpack the directory anywhere; `river` finds everything relative to itself.

## Quickstart

```
river init backup.yaml                   # edit: paths to back up, retention, encryption
river create ssh:me@host:/backups/box backup.yaml
river backup ssh:me@host:/backups/box    # run it from cron, as often as you like
river list ssh:me@host:/backups/box
river restore ssh:me@host:/backups/box latest --to /tmp/restored
river verify ssh:me@host:/backups/box
```

Encrypted backups (`encrypted: true`) need the key in the `river_key` environment variable, or `river_key_file`
pointing at a file that holds it. There is deliberately no command line flag for it. Lose the key, lose the backup.

## Commands

| command | |
|---|---|
| `init [file]` | example config to a file (stdout if omitted) |
| `create <url> [config]` | create a backup from a config (stdin if omitted or `-`) |
| `config <url> [config]` | show the stored config; with a file replace it (versions are kept) |
| `backup <url>` | add a new version of the configured `paths`; nothing is stored if nothing changed |
| `list <url> [--files <version>]` | versions (`<generation>:<n>` and time, tab separated), or the files of one version |
| `restore <url> [<version>] [--to <dir>] [--files <path>...]` | default version `latest`; in place, or under `dir` |
| `verify <url> [<version>]` | read everything of a version, write nothing |
| `delete <url> --yes` | remove the whole backup |

`-v` shows what the archiver does. Exit codes: `0` ok, `1` done but some files had problems (unreadable files),
`2` error, `3` this backup is already running on this machine.

Archives keep absolute names: `restore` without `--to` puts files back where they were. With `--to dir` the tree
appears under `dir/<absolute path>`; with `--files /a/b/c --to dir` the match lands in `dir/c`.

## Config

`river init` prints it with comments. `paths`, `exclude`/`include_only` (zpaq patterns), `method` (zpaq `-method`, default `1`),
`keep_incremental_backup_count`, `keep_full_backup_count`, `encrypted`.

A backup is a chain of *generations*; a generation is a full backup plus up to `keep_incremental_backup_count`
incremental versions. When it is full, the next backup starts a new generation (a full backup again). Beyond
`keep_full_backup_count` generations the oldest is deleted whole (sequential archives cannot be repacked).
Inside a generation unchanged data is stored once, so incremental versions are small.

## What is stored

Flat files at the backup root, nothing is ever overwritten:

```
config.<revision>.<random>.yaml   config + state (generations, versions, the files they consist of); sealed
arc<revision>.<random>.jpaq       one tome per backup run, named for the revision that wrote it
idx<revision>.<random>.jpaq       the index of a generation, a new one per version
```

`revision` is a counter (5 digits) of committed changes (a backup run or a config change), `random` is 32 bits of hex.
A run uploads the tome, then the new index, then **commits by writing `config.<revision+1>.<random>.yaml`**, which
names every file of the backup. The last line of a config is its seal, `mac: <salt>.<hash>`: SHA-256 of the file, or
for encrypted backups an HMAC-SHA256 under a key derived from `river_key` with scrypt (N=131072, a fresh salt per
file), so that nobody without the key can make river believe in another config. The latest backup state is the highest
revision with a complete config. A config that was only partly uploaded is ignored; a complete one that does not
verify (wrong key, tampering) is an error, never skipped. The config has no secrets and no file names of your data.

So the storage needs no atomic operations: a failed or killed run leaves unreferenced files and no config, and
nothing committed is touched. After a commit river removes what the latest and the previous revision do not refer
to and that is older than the latest revision (newer files may belong to a run that is still going): old generations,
old indexes, junk of failed runs. The previous revision is kept as a fallback. Details, and why this is safe, are in
[docs/remote-protocol.md](docs/remote-protocol.md).

Concurrent backups of one url are a mistake, not a supported mode. They do not corrupt anything (two configs of the
same revision are both valid; the next backup continues from either), but one of the runs may not become part of the
history, and a run that sees another one commit its revision first refuses to commit. The same backup url cannot be
started twice on one machine (file lock in `~/.cache/river`, `river_lock_dir` to change).

## Drivers

`drivers/<name>/{upload,download,delete,list}`, executable, called as `<script> <url path> <argument>`:

- `upload <file>`: the file arrives on **stdin**; exit 0 only if all of it was stored
- `download <file>`: write the file to **stdout**
- `delete <file>`: delete the file; a missing file is not an error
- `list <prefix>`: the names of the files starting with prefix, one per line; no files is an empty output with exit 0

File names are flat (no directories). A non-zero exit code is a failure, **including a listing or download that is
incomplete**; stderr goes to the user. Once `upload` has exited with 0, `download` and `list` must show the file (a
partially visible file while it uploads, or after it failed, is fine). The url `ssh:me@host:/data` calls the scripts in
`drivers/ssh` with `me@host:/data`. Included: `local` (a directory), `ssh` (`SSH_OPTS` for options), `s3` (aws cli;
`s3://bucket/prefix`), `rclone` (`remote:path`). The `ssh`, `s3` and `rclone` scripts are thin and untested against
real servers here; the test suite runs on `local`.

## Tests

`mvn test` covers rolling and retention, restoring every version, encryption (wrong or missing key), exclude/include,
empty updates, the config seal and file names, torn and planted configs, concurrent commits, and fault injection at every
step of a backup (tome, index, commit, cleanup, listing, downloads) checking that the last committed version always
restores bit-exact.
