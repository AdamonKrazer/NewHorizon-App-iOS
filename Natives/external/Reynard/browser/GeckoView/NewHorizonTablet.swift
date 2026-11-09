import Foundation
import AVFoundation

/// Local in-game notices. No OS notification permissions or background delivery.
/// Mirrors Android TabletNotificationState limits and suppresses history replays.
@MainActor
final class NHTabletNotifications {
    var stateChanged: (([String:Any]) -> Void)?
    var showNotice: ((String) -> Void)?
    var isVisible: (() -> Bool)?
    private var accountID = ""
    private var reading = ""
    private var incoming = 0
    private var unread: [String:Int] = [:]
    private var peers: [String] = []
    private var messages: Set<String> = []
    private var messageOrder: [String] = []
    private var requests: Set<String> = []
    private var lastSound: TimeInterval = -.infinity
    private var player: AVAudioPlayer?
    private let settings = UserDefaults.standard
    private var sound: Bool { (settings.object(forKey:"nh.tablet.sound") as? Bool) ?? true }
    private var popups: Bool { (settings.object(forKey:"nh.tablet.popups") as? Bool) ?? true }

    func reset() {
        accountID = ""; reading = ""; incoming = 0; unread.removeAll(); peers.removeAll()
        messages.removeAll(); messageOrder.removeAll(); requests.removeAll(); lastSound = -.infinity
        player?.stop(); showNotice?("")
    }
    func account(_ id: String) {
        if id != accountID { reset(); accountID = id }
    }
    func read(_ peer: String) {
        reading = peer; unread.removeValue(forKey:peer); peers.removeAll { $0 == peer }; publish()
    }
    func configure(_ values: [String:Any]) -> Bool {
        guard let sound = values["sound"] as? Bool, let popups = values["popups"] as? Bool else { return false }
        settings.set(sound,forKey:"nh.tablet.sound"); settings.set(popups,forKey:"nh.tablet.popups")
        if !popups { showNotice?("") }
        publish(); return true
    }
    func publish() {
        stateChanged?(["kind":"notification_state","total":incoming+unread.values.reduce(0,+),
                       "incoming":incoming,"sound":sound,"popups":popups,"tone":"Horizon","unread":unread])
    }
    func receive(selfID: String, event: [String:Any]) {
        account(selfID)
        var notice: String?
        switch event["kind"] as? String {
        case "message":
            if let message = event["message"] as? [String:Any], let id = message["id"] as? String,
               let from = message["from"] as? String, let to = message["to"] as? String,
               !accountID.isEmpty, !id.isEmpty, id.utf16.count <= 256, from != accountID, to == accountID,
               UUID(uuidString:from) != nil, messages.insert(id).inserted {
                messageOrder.append(id)
                if messageOrder.count > 1024 { messages.remove(messageOrder.removeFirst()) }
                if from != reading {
                    if unread[from] == nil {
                        if peers.count >= 512 { unread.removeValue(forKey:peers.removeFirst()) }
                        peers.append(from)
                    }
                    unread[from] = min(999,(unread[from] ?? 0)+1)
                    notice = "Nova mensagem · \(Self.clean(message["name"],40))\n\(Self.clean(message["text"],100))"
                }
            }
        case "social":
            if let peer = event["peer"] as? String, !accountID.isEmpty, peer != accountID, UUID(uuidString:peer) != nil {
                let action = event["action"] as? String, relation = event["relation"] as? String
                if relation == "incoming", action == "friend_request", requests.count < 9999, requests.insert(peer).inserted {
                    incoming = min(9999,incoming+1)
                    notice = "Pedido de amizade · \(Self.clean(event["name"],40))\nQuer ser seu amigo"
                } else if relation != "incoming", requests.remove(peer) != nil { incoming = max(0,incoming-1) }
                if action == "friend_remove" { unread.removeValue(forKey:peer); peers.removeAll { $0 == peer } }
            }
        case "contacts":
            incoming = min(9999,max(0,(event["incoming"] as? NSNumber)?.intValue ?? 0))
            if incoming == 0 { requests.removeAll() }
            else {
                for contact in (event["contacts"] as? [[String:Any]] ?? []).prefix(9999) {
                    if contact["relation"] as? String == "incoming", let id = contact["id"] as? String,
                       UUID(uuidString:id) != nil, requests.count < 9999 { requests.insert(id) }
                }
            }
        default: break // history/state replays never create notices
        }
        if let notice, isVisible?() == true {
            if popups { showNotice?(notice) }
            if sound { play() }
        }
        publish()
    }
    func play() {
        let now = ProcessInfo.processInfo.systemUptime
        guard isVisible?() == true, now-lastSound >= 1.2 else { return }
        if player == nil, let url = Bundle.main.url(forResource:"notification-horizon",withExtension:"wav",subdirectory:"newhorizon/thin-ui/minepad") {
            player = try? AVAudioPlayer(contentsOf:url); player?.volume = 0.45
        }
        if let player { lastSound = now; player.currentTime = 0; player.play() }
    }
    private static func clean(_ value: Any?, _ limit: Int) -> String {
        let text = (value as? String ?? "").replacingOccurrences(of:"[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]",with:" ",options:.regularExpression)
        return text.count > limit ? String(text.prefix(limit))+"…" : text
    }
}
