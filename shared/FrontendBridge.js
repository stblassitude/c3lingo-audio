/*
 * Bridge between the c3lingo listen frontend and the native player, implementing
 * version 2 of the native bridge protocol (docs/playback-api.md in listen-frontend).
 *
 * Injected at document start. Page → app: window.c3lingoNative.ready(), play(channel),
 * stop() and setMuted(muted). App → page: "c3lingo:state" events with the full state,
 * dispatched through window.__c3lingoNativeState().
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

    const bridge = Object.freeze({
        ready: () => post({ cmd: "ready" }),
        play: (channel) => post({ cmd: "play", channel: channelInfo(channel) }),
        stop: () => post({ cmd: "stop" }),
        setMuted: (muted) => post({ cmd: "setMuted", muted: !!muted }),
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
