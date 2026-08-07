(() => {
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __esm = (fn, res, err) => function __init() {
    if (err) throw err[0];
    try {
      return fn && (res = (0, fn[__getOwnPropNames(fn)[0]])(fn = 0)), res;
    } catch (e) {
      throw err = [e], e;
    }
  };
  var __commonJS = (cb, mod) => function __require() {
    try {
      return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
    } catch (e) {
      throw mod = 0, e;
    }
  };

  // node_modules/bgutils-js/dist/utils/constants.js
  var init_constants = __esm({
    "node_modules/bgutils-js/dist/utils/constants.js"() {
    }
  });

  // node_modules/bgutils-js/dist/utils/helpers.js
  function base64ToU8(base64) {
    let base64Mod;
    if (base64urlCharRegex.test(base64)) {
      base64Mod = base64.replace(base64urlCharRegex, function(match) {
        return base64urlToBase64Map[match];
      });
    } else {
      base64Mod = base64;
    }
    base64Mod = atob(base64Mod);
    return new Uint8Array([...base64Mod].map((char) => char.charCodeAt(0)));
  }
  function u8ToBase64(u8, base64url = false) {
    const result = btoa(String.fromCharCode(...u8));
    if (base64url) {
      return result.replace(/\+/g, "-").replace(/\//g, "_");
    }
    return result;
  }
  var base64urlCharRegex, base64urlToBase64Map, DeferredPromise, BgError;
  var init_helpers = __esm({
    "node_modules/bgutils-js/dist/utils/helpers.js"() {
      init_constants();
      base64urlCharRegex = /[-_.]/g;
      base64urlToBase64Map = {
        "-": "+",
        _: "/",
        ".": "="
      };
      DeferredPromise = class {
        promise;
        resolve;
        reject;
        constructor() {
          this.promise = new Promise((resolve, reject) => {
            this.resolve = resolve;
            this.reject = reject;
          });
        }
      };
      BgError = class extends TypeError {
        info;
        constructor(message, info) {
          super(message);
          this.name = "BgError";
          if (info)
            this.info = info;
        }
      };
    }
  });

  // node_modules/bgutils-js/dist/utils/EventEmitterLike.js
  var EventEmitterLike;
  var init_EventEmitterLike = __esm({
    "node_modules/bgutils-js/dist/utils/EventEmitterLike.js"() {
      EventEmitterLike = class {
        #listeners = /* @__PURE__ */ new Map();
        #onceWrappers = /* @__PURE__ */ new Map();
        emit(type, ...args) {
          const listeners = this.#listeners.get(type);
          if (!listeners || listeners.size === 0)
            return;
          for (const listener of [...listeners]) {
            listener(...args);
          }
        }
        on(type, listener) {
          let listeners = this.#listeners.get(type);
          if (!listeners) {
            listeners = /* @__PURE__ */ new Set();
            this.#listeners.set(type, listeners);
          }
          listeners.add(listener);
        }
        once(type, listener) {
          const wrapper = (...args) => {
            this.off(type, listener);
            listener(...args);
          };
          let wrappersByType = this.#onceWrappers.get(listener);
          if (!wrappersByType) {
            wrappersByType = /* @__PURE__ */ new Map();
            this.#onceWrappers.set(listener, wrappersByType);
          }
          wrappersByType.set(type, wrapper);
          this.on(type, wrapper);
        }
        off(type, listener) {
          const listeners = this.#listeners.get(type);
          if (!listeners)
            return;
          let target = listener;
          const wrappersByType = this.#onceWrappers.get(listener);
          if (wrappersByType) {
            const onceWrapper = wrappersByType.get(type);
            if (onceWrapper) {
              target = onceWrapper;
              wrappersByType.delete(type);
              if (wrappersByType.size === 0)
                this.#onceWrappers.delete(listener);
            }
          }
          listeners.delete(target);
          if (listeners.size === 0)
            this.#listeners.delete(type);
        }
        removeAllListeners(type) {
          if (!type) {
            this.#listeners.clear();
            this.#onceWrappers.clear();
            return;
          }
          this.#listeners.delete(type);
          for (const [listener, wrappersByType] of this.#onceWrappers.entries()) {
            wrappersByType.delete(type);
            if (wrappersByType.size === 0)
              this.#onceWrappers.delete(listener);
          }
        }
      };
    }
  });

  // node_modules/bgutils-js/dist/core/BotGuardClient.js
  var BotGuardClient;
  var init_BotGuardClient = __esm({
    "node_modules/bgutils-js/dist/core/BotGuardClient.js"() {
      init_helpers();
      init_EventEmitterLike();
      BotGuardClient = class _BotGuardClient extends EventEmitterLike {
        vm;
        program;
        userInteractionElement;
        syncSnapshotFunction;
        deferredVmFunctions = new DeferredPromise();
        defaultTimeout = 3e3;
        on(type, listener) {
          super.on(type, listener);
        }
        off(type, listener) {
          super.off(type, listener);
        }
        constructor(options) {
          super();
          if (!options.globalObject || !options.globalName || !options.program) {
            throw new BgError("Invalid options", { options });
          }
          this.userInteractionElement = options.userInteractionElement;
          this.vm = options.globalObject[options.globalName];
          this.program = options.program;
        }
        /**
         * Factory method to create and load a BotGuardClient instance.
         * @param options - Configuration options for the BotGuardClient.
         * @returns A loaded BotGuardClient instance.
         */
        static async create(options) {
          return await new _BotGuardClient(options).load();
        }
        async load() {
          if (!this.vm)
            throw new BgError("EGOU: BotGuard unavailable");
          if (!this.vm.a)
            throw new BgError("ELIU: BotGuard initialization function unavailable");
          const vmSetupCallback = (asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) => {
            this.deferredVmFunctions.resolve({
              asyncSnapshotFunction,
              shutdownFunction,
              passEventFunction,
              checkCameraFunction
            });
          };
          const logEvent = (event, elapsedTime) => {
            this.emit("record-bg-event", { event, elapsedTime });
          };
          const incrementClientErrorCount = (errorCode) => {
            this.emit("increment-client-error-count", { errorCode });
          };
          const recordPayloadSize = (payloadSize) => {
            this.emit("record-payload-size", { payloadSize });
          };
          const recordLatency = (latency, et) => {
            this.emit("record-latency", { latency, et });
          };
          const incrementEventCount = (event) => {
            this.emit("increment-bg-event-count", { event });
          };
          const loggerFunctions = [
            logEvent,
            incrementClientErrorCount,
            recordPayloadSize,
            recordLatency,
            incrementEventCount
          ];
          const vmTelemetryCallback = (latency, eventFlag1, eventFlag2) => {
            let event = "k";
            if (eventFlag1) {
              event = "h";
            } else if (eventFlag2) {
              event = "u";
            }
            incrementEventCount(event);
            logEvent(event, latency);
          };
          try {
            this.syncSnapshotFunction = await this.vm.a(this.program, vmSetupCallback, true, this.userInteractionElement, vmTelemetryCallback, [[], []], void 0, false, loggerFunctions)?.[0];
          } catch (error) {
            throw new BgError("Could not load program", { error });
          }
          return this;
        }
        /**
         * Calls a VM function with a timeout.
         * @param vmFunctionName - The name of the VM function to execute.
         * @param timeout - The timeout in milliseconds.
         * @param args - The arguments to pass to the VM function.
         */
        async execute(vmFunctionName, timeout, ...args) {
          return await Promise.race([
            (async () => {
              const vmFunctions = await this.deferredVmFunctions.promise;
              const vmFunction = vmFunctions[vmFunctionName];
              if (!vmFunction)
                throw new BgError(`${vmFunctionName} function not found`);
              return vmFunction(...args);
            })(),
            new Promise((_, reject) => setTimeout(() => reject(new BgError("VM operation timed out")), timeout))
          ]);
        }
        /**
         * Takes a snapshot asynchronously.
         * @returns The snapshot result.
         * @example
         * ```ts
         * const result = await botguard.snapshot({
         *   contentBinding: {
         *     c: "a=6&a2=10&b=SZWDwKVIuixOp7Y4euGTgwckbJA&c=1729143849&d=1&t=7200&c1a=1&c6a=1&c6b=1&hh=HrMb5mRWTyxGJphDr0nW2Oxonh0_wl2BDqWuLHyeKLo",
         *     e: "ENGAGEMENT_TYPE_VIDEO_LIKE",
         *     encryptedVideoId: "P-vC09ZJcnM"
         *    }
         * });
         *
         * console.log(result);
         * ```
         */
        async snapshot(args, timeout = this.defaultTimeout) {
          return await new Promise(async (resolve, reject) => {
            await this.execute("asyncSnapshotFunction", timeout, (response) => resolve(response), [
              args.contentBinding,
              args.signedTimestamp,
              args.webPoSignalOutput,
              args.skipPrivacyBuffer
            ]).catch(reject);
          });
        }
        /**
         * Passes an event to the VM.
         */
        async passEvent(args, timeout = this.defaultTimeout) {
          return this.execute("passEventFunction", timeout, args);
        }
        /**
         * Checks the "camera".
         */
        async checkCamera(args, timeout = this.defaultTimeout) {
          return this.execute("checkCameraFunction", timeout, args);
        }
        /**
         * Shuts down the VM. Once called, the VM is no longer usable.
         */
        async shutdown(timeout = this.defaultTimeout) {
          return this.execute("shutdownFunction", timeout);
        }
        /**
         * Takes a snapshot synchronously.
         * @returns The snapshot result.
         */
        async snapshotSynchronous(args) {
          if (!this.syncSnapshotFunction)
            throw new BgError("Synchronous snapshot function not found");
          return this.syncSnapshotFunction([
            args.contentBinding,
            args.signedTimestamp,
            args.webPoSignalOutput,
            args.skipPrivacyBuffer
          ]);
        }
      };
    }
  });

  // node_modules/bgutils-js/dist/core/ChallengeFetcher.js
  var init_ChallengeFetcher = __esm({
    "node_modules/bgutils-js/dist/core/ChallengeFetcher.js"() {
      init_helpers();
    }
  });

  // node_modules/bgutils-js/dist/exports/botguard.js
  var init_botguard = __esm({
    "node_modules/bgutils-js/dist/exports/botguard.js"() {
      init_BotGuardClient();
      init_ChallengeFetcher();
    }
  });

  // node_modules/bgutils-js/dist/core/WebPoMinter.js
  var WebPoMinter;
  var init_WebPoMinter = __esm({
    "node_modules/bgutils-js/dist/core/WebPoMinter.js"() {
      init_helpers();
      WebPoMinter = class _WebPoMinter {
        mintCallback;
        constructor(mintCallback) {
          this.mintCallback = mintCallback;
        }
        /**
         * Factory method to create a WebPoMinter instance.
         * @param integrityTokenResponse - The integrity token response object.
         * @param webPoSignalOutput - The output array containing the minter function.
         */
        static async create(integrityTokenResponse, webPoSignalOutput) {
          const getMinter = webPoSignalOutput[0];
          if (!getMinter)
            throw new BgError("PMD:Undefined");
          if (!integrityTokenResponse.integrityToken)
            throw new BgError("No integrity token provided", { integrityTokenResponse });
          const mintCallback = await getMinter(base64ToU8(integrityTokenResponse.integrityToken));
          if (!(mintCallback instanceof Function))
            throw new BgError("APF:Failed");
          return new _WebPoMinter(mintCallback);
        }
        /**
         * Mints a proof and returns it as a web-safe base64 string.
         * @param contentBinding - A Visitor ID, Video ID, or Data Sync ID.
         */
        async mintAsWebsafeString(contentBinding) {
          return u8ToBase64(await this.mint(contentBinding), true);
        }
        /**
         * Mints a proof and returns it as a Uint8Array.
         * @param contentBinding - A Visitor ID, Video ID, or Data Sync ID.
         */
        async mint(contentBinding) {
          const result = await this.mintCallback(new TextEncoder().encode(contentBinding));
          if (!result)
            throw new BgError("YNJ:Undefined");
          if (!(result instanceof Uint8Array))
            throw new BgError("ODM:Invalid");
          return result;
        }
      };
    }
  });

  // node_modules/bgutils-js/dist/exports/webpo.js
  var init_webpo = __esm({
    "node_modules/bgutils-js/dist/exports/webpo.js"() {
      init_WebPoMinter();
    }
  });

  // entry.js
  var require_entry = __commonJS({
    "entry.js"() {
      init_botguard();
      init_webpo();
      window.__poToken = { status: "idle", token: null, error: null };
      window.__runSnapshot = function(program, globalName, contentBinding) {
        window.__poToken = { status: "running", token: null, error: null };
        (async () => {
          try {
            const globalObject = window;
            const client = await BotGuardClient.create({ globalObject, globalName, program });
            const webPoSignalOutput = [];
            // 对齐 FreeTube:snapshot 只传 webPoSignalOutput,不带 contentBinding。
            // 带占位符 c(b=PLACEHOLDER&hh=PLACEHOLDER) 的 contentBinding 会让 VM 不产生 minter,
            // 导致 webPoSignalOutput 空 → WebPoMinter.create 报 PMD:Undefined。
            // 视频绑定在 mint 阶段用 videoId 完成(mintAsWebsafeString(videoId))。
            // 对齐 FreeTube botGuardScript.js:snapshot({ webPoSignalOutput }, 10_000) 不带
            // skipPrivacyBuffer(§6.7 row 26 真机 adaptive=0 定位:token 判无效,先去掉该差异重测)。
            const botguardResponse = await client.snapshot({ webPoSignalOutput });
            // 诊断:确认 minter 是否真的产生(UA 修正后应 length>0 且 [0] 是 function)。
            // console.log 会被 evaluateJavascript 捕获为 null,改用 __diag。
            window.__diag = { length: webPoSignalOutput.length, isFunc: typeof webPoSignalOutput[0] };
            window.__poToken = { status: "snapshot-done", token: null, error: null, botguardResponse, webPoSignalOutput };
          } catch (e) {
            window.__poToken = { status: "error", token: null, error: String(e && e.stack || e) };
          }
        })();
      };
      window.__mint = function(integrityToken, contentBinding) {
        const prev = window.__poToken;
        window.__poToken = { status: "minting", token: null, error: null };
        (async () => {
          try {
            const webPoSignalOutput = prev.webPoSignalOutput;
            const minter = await WebPoMinter.create({ integrityToken }, webPoSignalOutput);
            const token = await minter.mintAsWebsafeString(contentBinding);
            window.__poToken = { status: "done", token, error: null };
          } catch (e) {
            window.__poToken = { status: "error", token: null, error: String(e && e.stack || e) };
          }
        })();
      };
    }
  });
  require_entry();
})();
