const path = require("path");
const resources = path.resolve(__dirname, "kotlin");
const sqliteWasm = path.resolve(__dirname, "../../node_modules/@sqlite.org/sqlite-wasm/dist");
for (const pattern of [path.join(resources, "*.mjs"), path.join(sqliteWasm, "*")]) {
  config.files.push({ pattern, served: true, watched: false, included: false, nocache: true });
}
config.proxies = config.proxies || {};
for (const file of ["sqlite-opfs.test-worker.mjs", "sqlite-opfs-worker-core.mjs"]) {
  config.proxies[`/${file}`] = path.join("/absolute", resources, file);
}
config.proxies["/sqlite-wasm/"] = path.join("/absolute", sqliteWasm) + "/";
config.mime = { "application/javascript": ["mjs"], "application/wasm": ["wasm"] };
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 60000 });
config.browserNoActivityTimeout = 120000;
