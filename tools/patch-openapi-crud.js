const fs = require("fs");
const path =
  "c:/Git/drapi-iws-design/extension/src/main/resources/openapi/openapi.design.json";
const doc = JSON.parse(fs.readFileSync(path, "utf8"));
doc.info.version = "0.20.1";

const skip = new Set(["/acl", "/applicationproperties"]);

function clone(o) {
  return JSON.parse(JSON.stringify(o));
}

for (const [p, node] of Object.entries(doc.paths)) {
  if (!node.put || !node.put.operationId || !node.put.operationId.startsWith("create")) {
    continue;
  }
  if (p.includes("{") || skip.has(p)) continue;

  const createId = node.put.operationId;
  const suffix = createId.slice("create".length); // Form
  const createIdPost = "create" + suffix;
  const upsertId = "upsert" + suffix;
  const updateId = "update" + suffix;
  const itemPath = p + "/{name}";
  const hasItem = !!doc.paths[itemPath];

  // Deprecate collection PUT upsert for named types only
  if (hasItem) {
    node.put.deprecated = true;
    node.put.summary =
      "Upsert (deprecated) — use POST to create and PUT " + itemPath + " to update";
    if (node.put.description) {
      node.put.description = "[DEPRECATED upsert] " + node.put.description;
    }
  }

  const post = clone(node.put);
  delete post.deprecated;
  post.operationId = createIdPost;
  post.summary = hasItem
    ? "Create only (409 if exists); empty body uses default when name/kind provided"
    : "Create singleton only (409 if exists); empty body uses default";
  post.description =
    "Create-only. Returns dxl. Responds with conflict if the element already exists.";
  if (!post.requestBody) post.requestBody = { required: false, content: { "text/xml": { schema: { type: "string" } } } };
  post.requestBody.required = false;
  post.parameters = clone(node.parameters || node.put.parameters || []);
  if (!Array.isArray(post.parameters)) post.parameters = [];
  // ensure nsfPath from path-level
  if (node.parameters) post.parameters = clone(node.parameters);

  if (hasItem) {
    if (!post.parameters.some((x) => x.name === "name")) {
      post.parameters.push({
        name: "name",
        description: "Design element name (required when body is empty)",
        schema: { type: "string" },
        in: "query",
        required: false,
      });
    }
    if (
      ["/agents", "/scriptlibraries", "/javasources"].includes(p) &&
      !post.parameters.some((x) => x.name === "kind")
    ) {
      post.parameters.push({
        name: "kind",
        description: "Kind (required when body is empty)",
        schema: { type: "string" },
        in: "query",
        required: false,
      });
    }
  }

  post.responses = post.responses || {};
  post.responses["409"] = { description: "Already exists" };
  post.responses["201"] = { description: "Created" };
  node.post = post;

  if (hasItem) {
    const item = doc.paths[itemPath];
    const putUpdate = clone(node.put);
    delete putUpdate.deprecated;
    putUpdate.operationId = updateId;
    putUpdate.summary = "Update only (404 if missing)";
    putUpdate.description = "Replace existing design element. Returns dxl.";
    putUpdate.requestBody = putUpdate.requestBody || {
      required: true,
      content: { "text/xml": { schema: { type: "string" } } },
    };
    putUpdate.requestBody.required = true;
    putUpdate.parameters = undefined; // use path-level params
    putUpdate.responses = putUpdate.responses || {};
    putUpdate.responses["404"] = { description: "Not found" };
    item.put = putUpdate;
    item.summary = "Retrieve, update, or delete";
  } else {
    // singleton PUT = update
    node.put.operationId = updateId;
    delete node.put.deprecated;
    node.put.summary = "Update only (404 if missing)";
    node.put.description = "Replace existing singleton. Returns dxl.";
    node.put.responses = node.put.responses || {};
    node.put.responses["404"] = { description: "Not found" };
  }
}

// /dbicon
if (!doc.paths["/dbicon"]) {
  const about = clone(doc.paths["/about"]);
  about.summary = "Database icon ($DBIcon)";
  about.description =
    "Singleton image resource named $DBIcon (not listed under /imagenames)";
  if (about.get) {
    about.get.operationId = "getDbIcon";
    about.get.summary = "Retrieve database icon as DXL";
  }
  if (about.post) {
    about.post.operationId = "postDbIcon";
    about.post.summary = "Create database icon";
  }
  if (about.put) {
    about.put.operationId = "updateDbIcon";
    about.put.summary = "Update database icon";
  }
  if (about.delete) {
    about.delete.operationId = "deleteDbIcon";
    about.delete.summary = "Delete database icon";
  }
  doc.paths["/dbicon"] = about;
}

// xspproperties DELETE
const xsp = doc.paths["/xspproperties"];
if (xsp && !xsp.delete) {
  xsp.delete = {
    tags: ["design-element"],
    operationId: "deleteXspProperties",
    summary: "Delete XPages xsp.properties",
    description: "Delete the WEB-INF/xsp.properties design note.",
    "x-keep-version": 1,
    security: [{ jwt: [] }],
    responses: {
      "200": { description: "Deleted" },
      "404": { description: "Not found" },
      "403": { $ref: "#/components/responses/403Forbidden" },
    },
  };
}

fs.writeFileSync(path, JSON.stringify(doc, null, 2) + "\n", "utf8");
console.log("patched", doc.info.version);
console.log("forms.post", doc.paths["/forms"].post?.operationId);
console.log("forms.put.deprecated", doc.paths["/forms"].put?.deprecated);
console.log("forms/{name}.put", doc.paths["/forms/{name}"].put?.operationId);
console.log("about.post", doc.paths["/about"].post?.operationId);
console.log("about.put", doc.paths["/about"].put?.operationId);
console.log("dbicon.get", doc.paths["/dbicon"].get?.operationId);
console.log("xsp.delete", doc.paths["/xspproperties"].delete?.operationId);
