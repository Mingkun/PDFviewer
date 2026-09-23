const { contextBridge, shell } = require('electron');
contextBridge.exposeInMainWorld('electronOpen', (u) => shell.openExternal(u));
