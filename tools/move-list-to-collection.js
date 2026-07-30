const fs = require("fs");
const path =
  "c:/Git/drapi-iws-design/extension/src/main/resources/openapi/openapi.design.json";
const doc = JSON.parse(fs.readFileSync(path, "utf8"));
doc.info.version = "0.21.0";

// Build createX -> collection path
const createToCollection = {};
for (const [p, node] of Object.entries(doc.paths)) {
  if (p.includes("{")) continue;
  if (node.post && node.post.operationId && node.post.operationId.startsWith("create")) {
    createToCollection[node.post.operationId] = p;
  }
}

const namesPaths = Object.keys(doc.paths).filter((p) => /names$/i.test(p));
let moved = 0;
for (const namesPath of namesPaths) {
  const node = doc.paths[namesPath];
  const get = node.get;
  if (!get || !get.operationId || !get.operationId.startsWith("list")) {
    console.warn("skip (no list*):", namesPath, get?.operationId);
    continue;
  }
  const listId = get.operationId; // listAgents
  const suffix = listId.slice("list".length); // Agents
  const createId = "create" + suffix.replace(/s$/, (m, off, s) => {
    // listAgents -> createAgent (singularize crudely)
    return "";
  });
  // Better: listAgents -> Agents -> createAgent by dropping trailing s and lowercasing first? 
  // listScriptLibraries -> createScriptLibrary
  // Use map from list* to create* via known pairs
  const listToCreate = {
    listAgents: "createAgent",
    listForms: "createForm",
    listPages: "createPage",
    listOutlines: "createOutline",
    listFramesets: "createFrameset",
    listViews: "createView",
    listFolders: "createFolder",
    listSubforms: "createSubform",
    listSharedFields: "createSharedField",
    listSharedColumns: "createSharedColumn",
    listImages: "createImage",
    listFileResources: "createFileResource",
    listStyleSheets: "createStyleSheet",
    listThemes: "createTheme",
    listScriptLibraries: "createScriptLibrary",
    listSharedActions: "createSharedActions",
    listXPages: "createXPage",
    listCustomControls: "createCustomControl",
    listWebServices: "createWebService",
    listWebServiceConsumers: "createWebServiceConsumer",
    listNavigators: "createNavigator",
    listDataConnections: "createDataConnection",
    listJars: "createJar",
    listApplets: "createApplet",
    listJavaSources: "createJavaSource",
    listWiringProperties: "createWiringProperties",
    listCompositeApplications: "createCompositeApplication",
    listCompositeComponents: "createCompositeComponent",
  };
  const cId = listToCreate[listId];
  const collection = createToCollection[cId];
  if (!collection) {
    console.warn("no collection for", listId, cId);
    continue;
  }
  const col = doc.paths[collection];
  // Move GET list onto collection
  col.get = {
    ...get,
    summary: get.summary || "List names",
    description:
      get.description ||
      "List design element names in the NSF (name-only inventory).",
  };
  // Drop deprecated upsert PUT on collection
  if (col.put) {
    delete col.put;
  }
  col.summary = col.summary || "List or create";
  col.description =
    "List names (GET) or create (POST). Update/delete use /{name}.";
  delete doc.paths[namesPath];
  moved++;
  console.log(namesPath, "-> GET", collection, listId);
}

// Also remove any remaining collection PUT that is upsert*
for (const [p, node] of Object.entries(doc.paths)) {
  if (p.includes("{")) continue;
  if (node.put && node.put.operationId && node.put.operationId.startsWith("upsert")) {
    delete node.put;
    console.log("removed upsert PUT", p);
  }
}

fs.writeFileSync(path, JSON.stringify(doc, null, 2) + "\n");
console.log("moved", moved, "version", doc.info.version);
console.log(
  "agents",
  Object.keys(doc.paths["/agents"]).filter((k) =>
    ["get", "post", "put", "delete"].includes(k)
  )
);
console.log("agentnames left?", !!doc.paths["/agentnames"]);
console.log(
  "forms/{name}",
  Object.keys(doc.paths["/forms/{name}"]).filter((k) =>
    ["get", "post", "put", "delete"].includes(k)
  )
);
