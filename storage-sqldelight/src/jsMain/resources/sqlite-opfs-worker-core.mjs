async function open(sqlite3InitModule, dbName) {
  const sqlite3 = await sqlite3InitModule();
  if (dbName === ":memory:") return new sqlite3.oo1.DB(":memory:", "c");
  const safe = dbName.replace(/[^A-Za-z0-9._-]/g, "_");
  const name = `meshtastic-sahpool-${safe}`;
  // A failed installOpfsSAHPoolVfs runs removeVfs(), which deletes the pool directory and its databases,
  // and the likeliest failure is another worker still holding the pool's files. Install only as the lock holder.
  await holdUntilWorkerExits(name);
  const pool = await sqlite3.installOpfsSAHPoolVfs({ name, directory: `/meshtastic-sahpool/${safe}` });
  const db = new pool.OpfsSAHPoolDb(`/${safe}`);
  db.exec("PRAGMA foreign_keys=ON; PRAGMA synchronous=NORMAL;");
  return db;
}

/** Resolves once this worker holds the Web Lock [name]; the browser releases it when the worker ends. */
function holdUntilWorkerExits(name) {
  return new Promise((resolve, reject) => {
    navigator.locks
      .request(name, () => {
        resolve();
        return new Promise(() => {});
      })
      .catch(reject);
  });
}

function exec(db, sql, bind) {
  const stmt = db.prepare(sql);
  try {
    if (bind && bind.length) stmt.bind(bind);
    if (stmt.columnCount === 0) {
      stmt.step();
      return { values: [[db.changes()]] };
    }
    const values = [];
    while (stmt.step()) values.push(stmt.get([]));
    return { values };
  } finally {
    stmt.finalize();
  }
}

function handle(db, data) {
  switch (data && data.action) {
    case "exec":
      if (!data.sql) throw new Error("exec: Missing query string");
      return exec(db, data.sql, data.params);
    case "begin_transaction":
      db.exec("BEGIN TRANSACTION;");
      return { values: [] };
    case "end_transaction":
      db.exec("END TRANSACTION;");
      return { values: [] };
    case "rollback_transaction":
      db.exec("ROLLBACK TRANSACTION;");
      return { values: [] };
    default:
      throw new Error(`Unsupported action: ${data && data.action}`);
  }
}

export function serveSqliteWorker(sqlite3InitModule) {
  const dbName = new URL(self.location.href).searchParams.get("db") ?? "meshtastic.db";
  const ready = open(sqlite3InitModule, dbName);
  let queue = ready.catch(() => {});
  self.onmessage = (event) => {
    const data = event.data;
    queue = queue.then(async () => {
      try {
        const db = await ready;
        self.postMessage({ id: data.id, results: handle(db, data) });
      } catch (e) {
        self.postMessage({ id: data.id, error: { name: e?.name, message: String(e?.message ?? e) } });
      }
    });
  };
}
