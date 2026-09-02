// New Horizon's MCEF compatibility layer for Reynard.
// WebRender frames stay in IOSurface-backed GPU memory from Gecko through
// ANGLE/LTW; this file never snapshots a UIView or maps browser pixels.

import Darwin
import Foundation
import IOSurface
import UIKit

private enum NHNativeBridge {
    typealias SubmitSurface = @convention(c) (
        Int32, UInt32, Int32, Int32, UInt64
    ) -> Void
    typealias SubmitEvent = @convention(c) (
        Int32, UnsafePointer<CChar>?, UnsafePointer<CChar>?
    ) -> Void
    typealias ReleaseBrowser = @convention(c) (Int32) -> Void
    typealias RootView = @convention(c) () -> UnsafeMutableRawPointer?

    private static let process = dlopen(nil, RTLD_NOW)

    private static func symbol<T>(_ name: String, as type: T.Type) -> T? {
        guard let process, let pointer = dlsym(process, name) else { return nil }
        return unsafeBitCast(pointer, to: type)
    }

    static func submitSurface(
        browserID: Int32, surfaceID: UInt32, width: Int32,
        height: Int32, version: UInt64
    ) {
        symbol("nh_reynard_submit_gpu_surface", as: SubmitSurface.self)?(
            browserID, surfaceID, width, height, version
        )
    }

    static func submitEvent(browserID: Int32, type: String, payload: [String: Any]) {
        guard JSONSerialization.isValidJSONObject(payload),
              let data = try? JSONSerialization.data(withJSONObject: payload),
              let json = String(data: data, encoding: .utf8),
              let submit = symbol("nh_reynard_submit_event", as: SubmitEvent.self)
        else { return }
        type.withCString { typePointer in
            json.withCString { payloadPointer in
                submit(browserID, typePointer, payloadPointer)
            }
        }
    }

    static func release(browserID: Int32) {
        symbol("nh_reynard_release_browser", as: ReleaseBrowser.self)?(browserID)
    }

    static func rootView() -> UIView? {
        guard let pointer = symbol("nh_reynard_root_view", as: RootView.self)?()
        else { return nil }
        return Unmanaged<UIView>.fromOpaque(pointer).takeUnretainedValue()
    }
}

@MainActor
private final class NHMCEFPortListener: GeckoEventListenerInternal {
    weak var browser: NHMCEFBrowser?
    let portID: String

    init(browser: NHMCEFBrowser, portID: String) {
        self.browser = browser
        self.portID = portID
    }

    func handleMessage(type: String, message: [String: Any?]?) async throws -> Any? {
        browser?.handlePortEvent(type: type, message: message, portID: portID)
        return nil
    }
}

@MainActor
private final class NHMCEFBrowser: NSObject, GeckoEventListenerInternal,
    ContentDelegate, NavigationDelegate, ProgressDelegate
{
    let id: Int32
    let session = GeckoSession()
    let view = GeckoView(frame: CGRect(x: 0, y: 0, width: 16, height: 16))

    private var visible = true
    private var ports: [String: GeckoEventDispatcherWrapper] = [:]
    private var portListeners: [String: NHMCEFPortListener] = [:]
    private var mainPortID: String?
    private var retainedScripts: [String] = []
    private var nextQueryID = 1
    private var queryRoutes: [Int: (portID: String, pageID: Int)] = [:]
    private var nextNavigationID = 1
    private var pendingNavigations: [Int: CheckedContinuation<AllowOrDeny, Never>] = [:]

    init(id: Int32, url: String) {
        self.id = id
        super.init()
        session.contentDelegate = self
        session.navigationDelegate = self
        session.progressDelegate = self
        session.dispatcher.addListener(
            type: "GeckoView:CompositorSurfaceChanged", listener: self
        )
        session.dispatcher.addListener(
            type: "GeckoView:WebExtension:Connect", listener: self
        )
        session.open()
        view.session = session
        view.clipsToBounds = true
        view.isUserInteractionEnabled = false
        session.setActive(true)
        session.setFocused(false)
        resize(width: 16, height: 16)
        session.load(url.isEmpty ? "about:blank" : url)
        submit(type: "created")
    }

    deinit {
        NHNativeBridge.release(browserID: id)
    }

    func handleMessage(type: String, message: [String: Any?]?) async throws -> Any? {
        switch type {
        case "GeckoView:CompositorSurfaceChanged":
            publishCompositorSurface()
            return nil
        case "GeckoView:WebExtension:Connect":
            return connectNativePort(message)
        default:
            return nil
        }
    }

    func connectNativePort(_ message: [String: Any?]?) -> Bool {
        guard string(message?["nativeApp"]) == "new_horizon_mcef",
              let portID = string(message?["portId"]), !portID.isEmpty
        else { return false }
        if ports[portID] == nil {
            let dispatcher = GeckoEventDispatcherWrapper.lookup(byName: "port:\(portID)")
            let listener = NHMCEFPortListener(browser: self, portID: portID)
            dispatcher.addListener(type: "GeckoView:WebExtension:PortMessage", listener: listener)
            dispatcher.addListener(type: "GeckoView:WebExtension:Disconnect", listener: listener)
            ports[portID] = dispatcher
            portListeners[portID] = listener
        }
        return true
    }

    func handlePortEvent(type: String, message: [String: Any?]?, portID: String) {
        if type == "GeckoView:WebExtension:Disconnect" {
            ports.removeValue(forKey: portID)
            portListeners.removeValue(forKey: portID)
            if mainPortID == portID { mainPortID = nil }
            queryRoutes = queryRoutes.filter { $0.value.portID != portID }
            return
        }
        guard type == "GeckoView:WebExtension:PortMessage",
              var data = dictionary(message?["data"]),
              let messageType = data["type"] as? String
        else { return }

        switch messageType {
        case "frame-ready":
            if bool(data["mainFrame"]) { mainPortID = portID }
            for script in retainedScripts {
                post(to: portID, ["type": "eval", "code": script])
            }
        case "query":
            let pageID = integer(data["id"])
            let bridgeID = nextQueryID
            nextQueryID += 1
            queryRoutes[bridgeID] = (portID, pageID)
            data["id"] = bridgeID
            submit(type: "query", payload: data)
        case "console", "frame-load", "history", "popup":
            submit(type: messageType, payload: data)
        default:
            break
        }
    }

    func resize(width: Int, height: Int) {
        let pixelWidth = max(1, width)
        let pixelHeight = max(1, height)
        let scale = max(view.traitCollection.displayScale, 1)
        view.frame.size = CGSize(
            width: CGFloat(pixelWidth) / scale,
            height: CGFloat(pixelHeight) / scale
        )
        view.setNeedsLayout()
        view.layoutIfNeeded()
    }

    func load(_ url: String) {
        session.load(url.isEmpty ? "about:blank" : url)
    }

    func evaluate(_ code: String) {
        guard !code.isEmpty, !retainedScripts.contains(code) else { return }
        retainedScripts.append(code)
        for portID in ports.keys {
            post(to: portID, ["type": "eval", "code": code])
        }
    }

    func setVisible(_ value: Bool) {
        visible = value
        session.setActive(value)
    }

    func setFocused(_ value: Bool) {
        if value { setVisible(true) }
        session.setFocused(value)
    }

    func sendInput(_ payload: [String: Any]) {
        if payload["kind"] as? String == "mouse",
           integer(payload["eventType"]) == 501 {
            setFocused(true)
        }
        let targetPorts = mainPortID.map { [$0] } ?? Array(ports.keys)
        for portID in targetPorts {
            var message = payload
            message["type"] = "input"
            post(to: portID, message)
        }
    }

    func resolveQuery(
        id queryID: Int, success: Bool, errorCode: Int,
        response: String, persistent: Bool
    ) {
        guard let route = queryRoutes[queryID], ports[route.portID] != nil else { return }
        if !persistent { queryRoutes.removeValue(forKey: queryID) }
        post(to: route.portID, [
            "type": "query-result",
            "id": route.pageID,
            "success": success,
            "errorCode": errorCode,
            "response": response,
            "persistent": persistent,
        ])
    }

    func resolveNavigation(id requestID: Int, allow: Bool) {
        pendingNavigations.removeValue(forKey: requestID)?
            .resume(returning: allow ? .allow : .deny)
    }

    func showOverlay(in root: UIView, frame: CGRect, url: String) {
        if !url.isEmpty { load(url) }
        setVisible(true)
        setFocused(true)
        view.removeFromSuperview()
        view.frame = frame
        view.isUserInteractionEnabled = true
        root.addSubview(view)
    }

    func hideOverlay(in root: UIView?) {
        view.isUserInteractionEnabled = false
        view.removeFromSuperview()
        root?.insertSubview(view, at: 0)
        setFocused(false)
    }

    func close() {
        for continuation in pendingNavigations.values {
            continuation.resume(returning: .allow)
        }
        pendingNavigations.removeAll()
        view.removeFromSuperview()
        session.close()
        NHNativeBridge.release(browserID: id)
    }

    private func publishCompositorSurface() {
        guard visible, let info = session.window?.compositorSurfaceInfo(),
              let surfaceID = info["surfaceId"]?.uint32Value,
              let version = info["version"]?.uint64Value,
              let width = info["width"]?.int32Value,
              let height = info["height"]?.int32Value
        else { return }
        NHNativeBridge.submitSurface(
            browserID: id, surfaceID: surfaceID,
            width: width, height: height, version: version
        )
    }

    private func post(to portID: String, _ payload: [String: Any]) {
        ports[portID]?.dispatch(
            type: "GeckoView:WebExtension:PortMessageFromApp",
            message: ["message": payload]
        )
    }

    private func submit(type: String, payload: [String: Any] = [:]) {
        NHNativeBridge.submitEvent(browserID: id, type: type, payload: payload)
    }

    private func navigation(_ request: LoadRequest, mainFrame: Bool) async -> AllowOrDeny {
        let requestID = nextNavigationID
        nextNavigationID += 1
        submit(type: "navigation-request", payload: [
            "requestId": requestID,
            "url": request.uri,
            "triggerUrl": request.triggerUri ?? "",
            "mainFrame": mainFrame,
            "redirect": request.isRedirect,
            "userGesture": request.hasUserGesture,
            "directNavigation": request.isDirectNavigation,
        ])
        return await withCheckedContinuation { continuation in
            pendingNavigations[requestID] = continuation
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: 1_500_000_000)
                self?.resolveNavigation(id: requestID, allow: true)
            }
        }
    }

    // MARK: Gecko delegates

    func onTitleChange(session: GeckoSession, title: String) {
        submit(type: "title", payload: ["title": title])
    }

    func onCloseRequest(session: GeckoSession) { submit(type: "close-request") }
    func onCrash(session: GeckoSession) { submit(type: "crash") }
    func onKill(session: GeckoSession) { submit(type: "crash", payload: ["killed": true]) }
    func onFirstComposite(session: GeckoSession) { publishCompositorSurface() }

    func onLocationChange(
        session: GeckoSession, url: String?, permissions: [ContentPermission]
    ) {
        submit(type: "address-change", payload: [
            "url": url ?? "", "mainFrame": true, "userGesture": false,
        ])
    }

    func onLoadRequest(session: GeckoSession, request: LoadRequest) async -> AllowOrDeny {
        await navigation(request, mainFrame: true)
    }

    func onPreNavigation(session: GeckoSession, request: LoadRequest) async -> AllowOrDeny {
        .allow
    }

    func onSubframeLoadRequest(
        session: GeckoSession, request: LoadRequest
    ) async -> AllowOrDeny {
        await navigation(request, mainFrame: false)
    }

    func onNewSession(
        session: GeckoSession, uri: String, windowId: String,
        target: LoadRequestTarget
    ) async -> GeckoSession? {
        submit(type: "popup-created", payload: ["popupId": 0, "url": uri])
        return nil
    }

    func onPageStart(session: GeckoSession, url: String) {
        retainedScripts.removeAll()
        submit(type: "load-start", payload: ["url": url, "mainFrame": true])
        submit(type: "loading-state", payload: ["loading": true])
    }

    func onPageStop(session: GeckoSession, success: Bool) {
        submit(type: "load-end", payload: [
            "url": "", "mainFrame": true, "success": success,
            "httpStatus": success ? 200 : 0,
        ])
        submit(type: "loading-state", payload: ["loading": false])
    }

    func onProgressChange(session: GeckoSession, progress: Int) {}
}

@MainActor
private final class NHReynardMCEFManager: GeckoEventListenerInternal {
    static let shared = NHReynardMCEFManager()

    private var browsers: [Int32: NHMCEFBrowser] = [:]
    private var commandQueue: [(String, [String])] = []
    private var draining = false
    private var extensionReady = false

    private init() {
        GeckoEventDispatcherWrapper.runtimeInstance.addListener(
            type: "GeckoView:WebExtension:Connect", listener: self
        )
    }

    func enqueue(operation: String, arguments: [String]) {
        commandQueue.append((operation, arguments))
        guard !draining else { return }
        draining = true
        Task { @MainActor in await drainCommands() }
    }

    func handleMessage(type: String, message: [String: Any?]?) async throws -> Any? {
        guard type == "GeckoView:WebExtension:Connect",
              let browser = browser(for: message)
        else { return false }
        return browser.connectNativePort(message)
    }

    private func drainCommands() async {
        while !commandQueue.isEmpty {
            let command = commandQueue.removeFirst()
            await handle(operation: command.0, arguments: command.1)
        }
        draining = false
    }

    private func ensureBridgeExtension() async -> Bool {
        if extensionReady { return true }
        guard let resourceURL = Bundle.main.resourceURL else {
            NSLog("[NewHorizon/Reynard] Main bundle has no resource URL")
            return false
        }
        let extensionURL = resourceURL
            .appendingPathComponent("assets", isDirectory: true)
            .appendingPathComponent("gecko_mcef", isDirectory: true)
        do {
            try await AddonRuntime.shared.ensureBuiltIn(
                location: extensionURL.absoluteString,
                id: "mcef-bridge@newhorizon.local"
            )
            extensionReady = true
        } catch {
            NSLog("[NewHorizon/Reynard] MCEF extension install failed: \(error)")
        }
        return extensionReady
    }

    private func handle(operation: String, arguments: [String]) async {
        if operation.hasSuffix("N_DoMessageLoopWork") { return }
        if operation.hasSuffix("RemoteCreateBrowser") {
            guard await ensureBridgeExtension() else { return }
            let browserID = int32(arguments, 0, fallback: -1)
            guard browserID >= 0 else { return }
            browsers.removeValue(forKey: browserID)?.close()
            let browser = NHMCEFBrowser(
                id: browserID, url: string(arguments, 1, fallback: "about:blank")
            )
            browsers[browserID] = browser
            attachOffscreen(browser)
            return
        }

        let browserID = int32(arguments, 0, fallback: -1)
        if operation.hasSuffix("RemoteDestroyBrowser") {
            browsers.removeValue(forKey: browserID)?.close()
            return
        }
        guard let browser = browsers[browserID] else { return }

        if operation.hasSuffix("RemoteWasResized") {
            browser.resize(
                width: integer(arguments, 1, fallback: 1),
                height: integer(arguments, 2, fallback: 1)
            )
        } else if operation.hasSuffix("RemoteLoadURL") {
            browser.load(string(arguments, 1))
        } else if operation.hasSuffix("RemoteExecuteJavaScript") {
            browser.evaluate(string(arguments, 1))
        } else if operation.hasSuffix("RemoteSetFocus") {
            browser.setFocused(boolean(arguments, 1, fallback: true))
        } else if operation.hasSuffix("RemoteSetVisible") {
            browser.setVisible(boolean(arguments, 1, fallback: true))
        } else if operation.hasSuffix("RemoteSendMouseEvent") {
            let modifiers = integer(arguments, 6)
            browser.sendInput(modifierPayload(modifiers).merging([
                "kind": "mouse",
                "eventType": integer(arguments, 1, fallback: 503),
                "x": integer(arguments, 2),
                "y": integer(arguments, 3),
                "clickCount": integer(arguments, 4, fallback: 1),
                "button": integer(arguments, 5),
            ]) { _, new in new })
        } else if operation.hasSuffix("RemoteSendMouseWheelEvent") {
            let modifiers = integer(arguments, 5)
            browser.sendInput(modifierPayload(modifiers).merging([
                "kind": "wheel",
                "x": integer(arguments, 2),
                "y": integer(arguments, 3),
                "deltaY": double(arguments, 4),
            ]) { _, new in new })
        } else if operation.hasSuffix("RemoteSendKeyEvent") {
            let cefType = integer(arguments, 1, fallback: 401)
            let unicodeValue = integer(arguments, 3)
            let character = UnicodeScalar(unicodeValue).map(String.init) ?? ""
            let modifiers = integer(arguments, 4)
            browser.sendInput(modifierPayload(modifiers).merging([
                "kind": "key",
                "action": (cefType == 0 || cefType == 402) ? 1 : 0,
                "keyCode": integer(arguments, 2),
                "character": character,
                "scanCode": integer(arguments, 5),
            ]) { _, new in new })
        } else if operation == "__gecko_query_result" {
            browser.resolveQuery(
                id: integer(arguments, 1),
                success: boolean(arguments, 2),
                errorCode: integer(arguments, 3, fallback: -1),
                response: string(arguments, 4),
                persistent: boolean(arguments, 5)
            )
        } else if operation == "__gecko_navigation_result" {
            browser.resolveNavigation(
                id: integer(arguments, 1), allow: boolean(arguments, 2, fallback: true)
            )
        } else if operation == "__minepad_overlay_show" {
            guard let root = rootView() else { return }
            let scale = max(browser.view.traitCollection.displayScale, 1)
            browser.showOverlay(in: root, frame: CGRect(
                x: CGFloat(integer(arguments, 2)) / scale,
                y: CGFloat(integer(arguments, 3)) / scale,
                width: CGFloat(max(1, integer(arguments, 4))) / scale,
                height: CGFloat(max(1, integer(arguments, 5))) / scale
            ), url: string(arguments, 1))
        } else if operation == "__minepad_overlay_hide" {
            browser.hideOverlay(in: rootView())
        } else if operation.hasSuffix("RemoteUseCpuFrames") {
            NSLog("[NewHorizon/Reynard] CPU frame fallback rejected; IOSurface GPU path is mandatory")
        }
    }

    private func attachOffscreen(_ browser: NHMCEFBrowser) {
        rootView()?.insertSubview(browser.view, at: 0)
    }

    private func rootView() -> UIView? {
        NHNativeBridge.rootView()
    }

    private func browser(for message: [String: Any?]?) -> NHMCEFBrowser? {
        let sender = dictionary(message?["sender"])
        let sessionID = string(message?["sessionId"]) ?? string(sender?["sessionId"])
        if let sessionID,
           let match = browsers.values.first(where: { $0.session.id == sessionID }) {
            return match
        }
        return browsers.count == 1 ? browsers.values.first : nil
    }
}

@_cdecl("NHReynardMain")
public func NHReynardMain(
    _ argc: Int32,
    _ argv: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>
) -> Int32 {
    GeckoRuntime.main(argc: argc, argv: argv)
    return 0
}

@_cdecl("NHReynardHandleCommand")
public func NHReynardHandleCommand(
    _ operation: UnsafePointer<CChar>?,
    _ arguments: UnsafePointer<UnsafePointer<CChar>?>?,
    _ count: Int32
) {
    let operationValue = operation.map(String.init(cString:)) ?? ""
    var values: [String] = []
    if let arguments, count > 0 {
        values.reserveCapacity(Int(count))
        for index in 0..<Int(count) {
            values.append(arguments[index].map(String.init(cString:)) ?? "")
        }
    }
    Task { @MainActor in
        NHReynardMCEFManager.shared.enqueue(
            operation: operationValue, arguments: values
        )
    }
}

private func dictionary(_ value: Any?) -> [String: Any]? {
    if let value = value as? [String: Any] { return value }
    if let value = value as? NSDictionary {
        var result: [String: Any] = [:]
        for (key, entry) in value {
            if let key = key as? String { result[key] = entry }
        }
        return result
    }
    return nil
}

private func string(_ value: Any?) -> String? {
    if let value = value as? String { return value }
    if let value = value as? NSNumber { return value.stringValue }
    return nil
}

private func integer(_ value: Any?) -> Int {
    if let value = value as? NSNumber { return value.intValue }
    if let value = value as? String { return Int(value) ?? 0 }
    return 0
}

private func bool(_ value: Any?) -> Bool {
    if let value = value as? Bool { return value }
    if let value = value as? NSNumber { return value.boolValue }
    if let value = value as? String { return value == "true" || value == "1" }
    return false
}

private func string(_ values: [String], _ index: Int, fallback: String = "") -> String {
    values.indices.contains(index) ? values[index] : fallback
}

private func integer(_ values: [String], _ index: Int, fallback: Int = 0) -> Int {
    Int(string(values, index)) ?? fallback
}

private func int32(_ values: [String], _ index: Int, fallback: Int32) -> Int32 {
    Int32(string(values, index)) ?? fallback
}

private func double(_ values: [String], _ index: Int, fallback: Double = 0) -> Double {
    Double(string(values, index)) ?? fallback
}

private func boolean(_ values: [String], _ index: Int, fallback: Bool = false) -> Bool {
    guard values.indices.contains(index) else { return fallback }
    let value = values[index].lowercased()
    return value == "true" || value == "1"
}

private func modifierPayload(_ modifiers: Int) -> [String: Any] {
    [
        "shiftKey": (modifiers & (1 << 1)) != 0,
        "ctrlKey": (modifiers & (1 << 2)) != 0,
        "altKey": (modifiers & (1 << 3)) != 0,
        "metaKey": (modifiers & (1 << 7)) != 0,
    ]
}
