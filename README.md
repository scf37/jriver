# jriver

Incremental remote backups with no local temporary space. Java port of [river](https://github.com/scf37/river), built on
[jpaq](https://github.com/scf37/jpaq) (zpaq in Java with a streaming archive format) instead of the zpaq binary:
archives are streamed straight through three shell scripts, both when backing up and when restoring.

- **self-discovery**: `river help`, `river init` writes a commented config; everything else is a few commands
- **self-containment**: the config lives next to the backup (`river.conf`). To restore you need river, the backup url (and the key if encrypted)
- **easy extension**: a driver is three scripts (upload, download, delete) that move bytes over stdin/stdout
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
| `unlock <url>` | release the lock of a crashed backup |

`-v` shows what the archiver does. Exit codes: `0` ok, `1` done but some files had problems (unreadable files),
`2` error, `3` backup is locked.

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

```
<url>/river.conf                      config + state (generations, versions, trash); the only file that is ever overwritten
<url>/lock                            the lock, see below
<url>/g<time>/arc-000001.jpaq ...     one tome per backup run
<url>/g<time>/idx-<unix ms>-<hex>.jpaq  the index of the generation; a new name per version, old one deleted after commit
```

`river.conf` has no secrets and no file names. A backup run uploads the tome, then the new index, then **commits by
replacing `river.conf`** (it names the current index and lists the version). Anything uploaded but not committed (a
failed or killed run) is ignored and overwritten by the next run, so only `river.conf` must be replaced atomically
by the driver.

Drivers cannot list files, so river keeps a **trash** in `river.conf`: what a commit stops referring to (the
previous index, generations beyond `keep_full_backup_count`) is put in the trash by that same commit, then deleted.
A new generation is put in the trash before its first upload and taken out by its first commit, so a run that dies
leaves nothing that a later run cannot find. Every `backup` (and `delete`) deletes what is in the trash; a failed
delete is a warning and is retried by the next run. Entries deleted after a commit are dropped from the list at the
next write of `river.conf`.

## Locking

Concurrent backups would corrupt each other, so `backup`, `config` and `delete` take a lease lock:

- `lock` holds `<uuid> <expiry unix time>`. Take: read it, require it expired (plus 30 s clock skew allowance),
  write own uuid with expiry now + 5 min, wait 3 s, read again and require our uuid (racers: the last writer wins,
  the others back off). An unreadable or missing lock counts as held. Release writes expiry 0.
- A heartbeat renews it every minute. If it finds another uuid, or cannot renew in time, the run aborts and kills the
  running upload.
- Nothing is written without checking: before every blob is opened, and again before it is published (ending the
  upload is what makes a driver replace the file), the lock is re-read; before every chunk the wall clock is
  compared with the lease, so a process frozen for longer than the lease stops at its first write after waking up.
  An upload that fails a check is killed, with the processes the driver started, so that it is never completed.
  Before the commit the lock is checked again and `river.conf` must be unchanged.
- The same backup url cannot be started twice on one machine (file lock in `~/.cache/river`, `river_lock_dir` to change).

Known limits: needs clocks within about 30 s of each other (NTP); a process frozen between the last check of an upload
and its end (a few milliseconds) can still complete it after another process took over. A river process killed with
`kill -9` closes its pipes, which drivers cannot tell from a complete upload; that matters only for files no commit
refers to yet. `river verify` detects the damage.
A crashed run holds the lock for at most 5 minutes (`river unlock` to skip the wait).

## Drivers

`drivers/<name>/{upload,download,delete}`, executable, called as `<script> <url path> <relative path>`:

- `upload`: the file arrives on **stdin**
- `download`: write the file to **stdout**
- `delete`: delete the file or directory (recursively); a missing file is not an error

A non-zero exit code is a failure; stderr goes to the user. Only `river.conf` and `lock` need atomic replacement
(tmp file + rename, or an object store). An `upload` that is killed, or fails, must not leave a complete-looking file
behind: river kills the upload processes to abort an upload, and the driver must not publish what arrived until
then. `ssh` follows the data with a random end marker for this (the server sees a dropped connection as the end of
its input); `local` and `s3` publish only on success. The url `ssh:me@host:/data` calls the scripts in `drivers/ssh` with
`me@host:/data`. Included: `local` (a directory), `ssh` (`SSH_OPTS` for options), `s3` (aws cli; `s3://bucket/prefix`),
`rclone` (`remote:path`). The `ssh`, `s3` and `rclone` scripts are thin and untested against real servers here; the
test suite runs on `local`.

## Tests

`mvn test` covers rolling and retention, restoring every version, encryption, exclude/include, empty updates, lock
races, lease expiry and takeover, and fault injection at every step of a backup (tome, index, commit, cleanup,
downloads) checking that the last committed version always restores bit-exact and that leftovers are deleted by
later runs. The `ssh` upload is tested against a fake `ssh` that runs the remote side locally.
