# PostgreSQL MVCC

PostgreSQL uses multiversion concurrency control (MVCC) so that readers never block writers and writers never block readers. Instead of updating a row in place, every change writes a new version of the row, called a tuple, and each transaction sees the versions that were committed according to its snapshot.

## Tuple visibility

Each tuple carries two hidden transaction ids: `xmin`, the transaction that created it, and `xmax`, the transaction that deleted or replaced it. An `UPDATE` is effectively a delete plus an insert: the old tuple gets an `xmax` and a new tuple is written with a new `xmin`. When a transaction reads a table, it compares these ids with its snapshot, the set of transactions that were committed when the snapshot was taken, and only sees tuples created by committed transactions and not yet deleted by committed transactions.

## Isolation levels

The snapshot rules depend on the isolation level. In Read Committed, the default, each statement takes a new snapshot, so two `SELECT`s in the same transaction can see different data if another transaction commits between them. Repeatable Read takes one snapshot for the whole transaction, so repeated reads are stable; if it tries to update a row that a concurrent transaction already changed, it fails with a serialization error and the application must retry. Serializable adds predicate tracking on top of Repeatable Read and aborts transactions whose combined effect could not have happened in some serial order. PostgreSQL accepts Read Uncommitted but treats it as Read Committed: dirty reads never happen.

## Row locks still exist

MVCC removes read locks, not write conflicts. Two transactions updating the same row still serialize: the second waits until the first commits or rolls back. `SELECT ... FOR UPDATE` takes the same row lock explicitly, which is useful for read-modify-write sequences. `FOR UPDATE SKIP LOCKED` lets several workers take different rows from a queue table without waiting on each other.

## Dead tuples and VACUUM

Old tuple versions do not disappear when the transaction ends. Once no running transaction can see them, they become dead tuples that still take space. `VACUUM` marks that space reusable and updates the visibility map, which lets index-only scans skip heap visits. The autovacuum daemon runs it automatically based on how many rows changed. A long-running transaction, including one left idle in a session, holds back the oldest visible snapshot and prevents cleanup, which leads to table and index bloat.

## Transaction id wraparound

Transaction ids are 32-bit counters. To keep comparisons valid when the counter wraps around, `VACUUM` freezes old tuples, marking them as visible to every future transaction. If freezing falls too far behind, PostgreSQL first warns and eventually refuses new writes to protect the data. Monitoring the age of the oldest unfrozen transaction id per table, and making sure autovacuum keeps up, prevents that emergency.

## Practical consequences

Updates are more expensive than in an update-in-place engine because they write new tuples and new index entries; heap-only tuple (HOT) updates avoid the index work when no indexed column changes and the page has free space. Keeping transactions short, avoiding idle-in-transaction sessions and watching autovacuum are the everyday habits that keep an MVCC database healthy.
