import sqlite3InitModule from "@sqlite.org/sqlite-wasm";
import { serveSqliteWorker } from "./sqlite-opfs-worker-core.mjs";

serveSqliteWorker(sqlite3InitModule);
