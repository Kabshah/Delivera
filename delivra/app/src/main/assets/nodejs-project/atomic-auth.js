/**
 * atomic-auth.js — drop-in replacement for Baileys' useMultiFileAuthState
 * with §6.1-compliant behaviour:
 *
 *  1. ATOMIC WRITES: every creds/key file is written to "<file>.tmp" and then
 *     rename()d over the target. A process kill mid-write can never leave a
 *     torn JSON file behind — rename is atomic on the same filesystem.
 *
 *  2. STARTUP SESSION HEALTH CHECK: if creds.json EXISTS but fails to parse,
 *     we throw AUTH_STATE_CORRUPT instead of silently starting fresh creds
 *     (stock behaviour would silently de-register the session and demand a
 *     new pairing without telling the user why). Missing files are still fine.
 *
 * Ported from @whiskeysockets/baileys/lib/Utils/use-multi-file-auth-state.js
 * (pinned node_modules — deep imports are safe here; see .patch-delivra-tmpdir.md).
 */

import { Mutex } from 'async-mutex';
import { mkdir, readFile, rename, stat, unlink, writeFile } from 'fs/promises';
import { join } from 'path';
// Relative paths INTO node_modules on purpose: Baileys' package.json exports
// map blocks deep "@pkg/lib/..." specifiers, but plain file paths always work
// (node_modules is pinned/bundled with the app, see .patch-delivra-tmpdir.md).
import { proto } from './node_modules/@whiskeysockets/baileys/WAProto/index.js';
import { initAuthCreds } from './node_modules/@whiskeysockets/baileys/lib/Utils/auth-utils.js';
import { BufferJSON } from './node_modules/@whiskeysockets/baileys/lib/Utils/generics.js';

const fileLocks = new Map();
const getFileLock = (path) => {
    let mutex = fileLocks.get(path);
    if (!mutex) {
        mutex = new Mutex();
        fileLocks.set(path, mutex);
    }
    return mutex;
};

export class AuthStateCorruptError extends Error {
    constructor(file, cause) {
        super(`AUTH_STATE_CORRUPT: ${file} (${cause.message})`);
        this.name = 'AuthStateCorruptError';
        this.file = file;
        this.cause = cause;
    }
}

export const useAtomicFileAuthState = async (folder) => {

    // §6.1: temp file + atomic rename — never write session files in place.
    const writeData = async (data, file) => {
        const filePath = join(folder, fixFileName(file));
        const mutex = getFileLock(filePath);
        return mutex.acquire().then(async (release) => {
            const tmpPath = filePath + '.tmp';
            try {
                await writeFile(tmpPath, JSON.stringify(data, BufferJSON.replacer));
                await rename(tmpPath, filePath);
            }
            finally {
                release();
            }
        });
    };

    const readData = async (file, strict = false) => {
        const filePath = join(folder, fixFileName(file));
        const mutex = getFileLock(filePath);
        return mutex.acquire().then(async (release) => {
            try {
                const data = await readFile(filePath, { encoding: 'utf-8' });
                return JSON.parse(data, BufferJSON.reviver);
            }
            catch (error) {
                if (strict && error.code !== 'ENOENT') {
                    throw new AuthStateCorruptError(filePath, error);
                }
                return null;
            }
            finally {
                release();
            }
        });
    };

    const removeData = async (file) => {
        const filePath = join(folder, fixFileName(file));
        const mutex = getFileLock(filePath);
        return mutex.acquire().then(async (release) => {
            try {
                await unlink(filePath);
            }
            catch { }
            finally {
                release();
            }
        });
    };

    const folderInfo = await stat(folder).catch(() => { });
    if (folderInfo) {
        if (!folderInfo.isDirectory()) {
            throw new Error(`found something that is not a directory at ${folder}, either delete it or specify a different location`);
        }
    }
    else {
        await mkdir(folder, { recursive: true });
    }

    const fixFileName = (file) => file?.replace(/\//g, '__')?.replace(/:/g, '-');

    // STRICT on creds.json: a corrupt main credential file means the whole
    // session is broken — surface it (§6.1), don't silently re-register.
    const creds = (await readData('creds.json', /* strict */ true)) || initAuthCreds();

    return {
        state: {
            creds,
            keys: {
                get: async (type, ids) => {
                    const data = {};
                    await Promise.all(ids.map(async (id) => {
                        let value = await readData(`${type}-${id}.json`);
                        if (type === 'app-state-sync-key' && value) {
                            value = proto.Message.AppStateSyncKeyData.fromObject(value);
                        }
                        data[id] = value;
                    }));
                    return data;
                },
                set: async (data) => {
                    const tasks = [];
                    for (const category in data) {
                        for (const id in data[category]) {
                            const value = data[category][id];
                            const file = `${category}-${id}.json`;
                            tasks.push(value ? writeData(value, file) : removeData(file));
                        }
                    }
                    await Promise.all(tasks);
                }
            }
        },
        saveCreds: async () => {
            return writeData(creds, 'creds.json');
        }
    };
};
