import sqlite3InitModule from "/sqlite-wasm/index.mjs";
import { serveSqliteWorker } from "/sqlite-opfs-worker-core.mjs";

serveSqliteWorker(sqlite3InitModule);
