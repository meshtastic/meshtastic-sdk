async function open(sqlite3InitModule, dbName) {
  const sqlite3 = await sqlite3InitModule();
  if (dbName === ":memory:") return new sqlite3.oo1.DB(":memory:", "c");
  const safe = dbName.replace(/[^A-Za-z0-9._-]/g, "_");
  let lastError;
  for (let attempt = 0; attempt < 10; attempt++) {
    try {
      const pool = await sqlite3.installOpfsSAHPoolVfs({
        name: `meshtastic-sahpool-${safe}`,
        directory: `/meshtastic-sahpool/${safe}`,
      });
      const db = new pool.OpfsSAHPoolDb(`/${safe}`);
      db.exec("PRAGMA foreign_keys=ON; PRAGMA synchronous=NORMAL;");
      return db;
    } catch (e) {
      lastError = e;
      await new Promise((resolve) => setTimeout(resolve, 50 * (attempt + 1)));
    }
  }
  throw lastError;
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
