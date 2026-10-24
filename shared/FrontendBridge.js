/*
 * Bridge between the c3lingo listen frontend and the native player, implementing
 * version 2 of the native bridge protocol (docs/playback-api.md in listen-frontend).
 *
 * Injected at document start, after the app has set window.__c3lingoNativeConfig to
 * {language}, the app's UI language as a BCP 47 tag; the page uses it for its own texts.
 * Page → app: window.c3lingoNative.ready(), play(channel),
 * stop(), setMuted(muted) and the optional describe(channels), which tells the app the
 * hall, language and current talk of each channel for its player controls. App → page:
 * "c3lingo:state" events with the full state, dispatched through
 * window.__c3lingoNativeState().
 *
 * Only pages that declare <meta name="c3lingo-native-bridge" content="2"> see
 * window.c3lingoNative; any other page is left alone and plays audio itself. The meta
 * tag hasn't been parsed yet when this runs, so it is checked on access.
 *
 * Shared by both apps. The Apple app registers a WKScriptMessageHandler named "c3lingo";
 * the Android app registers a WebMessageListener named "c3lingoAndroid"
 * (WebViewCompat.addWebMessageListener), which only takes strings, so messages are sent
 * to it as JSON.
 */
(function () {
    "use strict";

    const PROTOCOL_VERSION = "2";

    const config = window.__c3lingoNativeConfig ?? {};
    delete window.__c3lingoNativeConfig;

    const webkitHandler = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.c3lingo;
    const androidHandler = window.c3lingoAndroid;
    let post;
    if (webkitHandler)
        post = (message) => webkitHandler.postMessage(message);
    else if (androidHandler)
        post = (message) => androidHandler.postMessage(JSON.stringify(message));
    else
        return;

    function channelInfo(channel) {
        return {
            name: String(channel.name),
            displayName: channel.displayName ? String(channel.displayName) : undefined,
            endpoint: channel.endpoint ? String(channel.endpoint) : undefined,
            location: channel.location ? String(channel.location) : undefined,
        };
    }

    // Plain data only: the apps validate each field themselves.
    function descriptions(channels) {
        return JSON.parse(JSON.stringify(channels ?? {}));
    }

    const bridge = Object.freeze({
        language: typeof config.language === "string" ? config.language : undefined,
        ready: () => post({ cmd: "ready" }),
        play: (channel) => post({ cmd: "play", channel: channelInfo(channel) }),
        stop: () => post({ cmd: "stop" }),
        setMuted: (muted) => post({ cmd: "setMuted", muted: !!muted }),
        describe: (channels) => post({ cmd: "describe", channels: descriptions(channels) }),
    });

    function pageSupportsBridge() {
        const meta = document.querySelector('meta[name="c3lingo-native-bridge"]');
        return !!meta && meta.content.trim() === PROTOCOL_VERSION;
    }

    Object.defineProperty(window, "c3lingoNative", {
        enumerable: true,
        get: () => pageSupportsBridge() ? bridge : undefined,
    });

    // Called by the app with the full state.
    window.__c3lingoNativeState = function (detail) {
        window.dispatchEvent(new CustomEvent("c3lingo:state", { detail: detail }));
    };
})();
