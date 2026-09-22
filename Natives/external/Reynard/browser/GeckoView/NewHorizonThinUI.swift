// UIKit presentation of the shared thin-client state. Browser pixels remain in GeckoView/IOSurface.
import Darwin
import Foundation
import UIKit

@MainActor
private final class NHHitThroughView: UIView {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return hit === self ? nil : hit
    }
}

@MainActor
private final class NHActionButton: UIButton {
    var action: (() -> Void)?
    init(_ title: String, action: @escaping () -> Void) {
        super.init(frame: .zero)
        self.action = action
        setTitle(title, for: .normal)
        titleLabel?.font = .monospacedSystemFont(ofSize: 12, weight: .medium)
        titleLabel?.numberOfLines = 2
        titleLabel?.textAlignment = .center
        backgroundColor = UIColor(white: 0.13, alpha: 0.85)
        layer.borderWidth = 1
        layer.borderColor = UIColor.gray.cgColor
        addTarget(self, action: #selector(tapped), for: .touchUpInside)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    @objc private func tapped() { action?() }
}

@MainActor
final class NHThinInterface: NSObject, UITextFieldDelegate, UICollectionViewDataSource,
    UICollectionViewDelegateFlowLayout {
    typealias Event = (Int32, String, String) -> Void
    private let emit: Event
    private let overlay = NHHitThroughView()
    private let hotbar = UIStackView()
    private let toolbar = UIStackView()
    private let chat = UILabel()
    private let status = UILabel()
    private let effects = UILabel()
    private let sidebar = UILabel()
    private let title = UILabel()
    private let debug = UILabel()
    private let playerList = UILabel()
    private let actionBar = UILabel()
    private let bosses = UILabel()
    private let input = UITextField()
    private let inventory = UIView()
    private let inventoryTitle = UILabel()
    private let search = UITextField()
    private let grid: UICollectionView
    private let images = NSCache<NSString, UIImage>()
    private var slots = [(amount: Int, material: String, name: String)](repeating: (0,"",""), count: 46)
    private var selected = 0
    private var creative = false
    private var creativeCatalog = false
    private var rightClick = false
    private var shiftClick = false
    private struct CreativeItem {
        let variant: Int
        let id: Int
        let maximum: Int
        let material: String
        let name: String
        let nbt: String
    }
    private var catalog: [CreativeItem] = []
    private var filtered: [CreativeItem] = []
    private var categories: [Int: Set<Int>] = [:]
    private var selectedCategory = 6
    private let categoryButton = UIButton(type: .system)
    private var messages: [String] = []
    private var inventoryControls: [UIView] = []
    private var respawn: NHActionButton!
    private var leaveRide: NHActionButton!
    private var hudHidden = false
    private var keyboardHeight: CGFloat = 0
    private var lastInventory = ""
    private let padPanel = UIView()
    let padViewport = UIView()
    private let padAddress = UITextField()
    private let padTabs = UISegmentedControl(items: [])
    private let padTabScroll = UIScrollView()
    private var padID: Int32 = -1
    var padNavigation: ((Int32, Int) -> Void)?
    var padLayout: (() -> Void)?
    private var padControls: [UIView] = []
    private var skinLayers: [String: UIImage] = [:]
    private var localPlayerID = ""
    private let playerHead = UIImageView()

    init(emit: @escaping Event) {
        self.emit = emit
        let layout = UICollectionViewFlowLayout()
        layout.minimumInteritemSpacing = 3
        layout.minimumLineSpacing = 3
        grid = UICollectionView(frame: .zero, collectionViewLayout: layout)
        super.init()
        images.totalCostLimit = 4 * 1024 * 1024
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        for label in [chat,status,effects,sidebar,title,debug,playerList,actionBar,bosses,inventoryTitle] {
            label.textColor = .white
            label.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
            label.numberOfLines = 0
            label.shadowColor = .black
            label.shadowOffset = CGSize(width: 1, height: 1)
            overlay.addSubview(label)
        }
        title.textAlignment = .center
        title.font = .boldSystemFont(ofSize: 25)
        actionBar.textAlignment = .center
        bosses.textAlignment = .center
        hotbar.axis = .horizontal
        hotbar.distribution = .fillEqually
        hotbar.spacing = 2
        overlay.addSubview(hotbar)
        for index in 0..<9 {
            hotbar.addArrangedSubview(NHActionButton("") { [weak self] in self?.key(Int32(49 + index)) })
        }
        toolbar.axis = .horizontal
        toolbar.spacing = 4
        toolbar.distribution = .fillEqually
        for (label, code) in [("Inventário",69),("Chat",84),("Câmera",294),("Jogadores",258),("Debug",292)] {
            toolbar.addArrangedSubview(NHActionButton(label) { [weak self] in self?.key(Int32(code)) })
        }
        overlay.addSubview(toolbar)
        respawn = NHActionButton("Renascer") { [weak self] in self?.emit(-1,"nh-combat-respawn","") }
        leaveRide = NHActionButton("Desmontar / acordar") { [weak self] in self?.emit(-1,"nh-riding-exit","") }
        overlay.addSubview(respawn)
        overlay.addSubview(leaveRide)
        respawn.isHidden = true
        leaveRide.isHidden = true
        configureField(input, placeholder: "Mensagem ou /comando")
        overlay.addSubview(input)
        input.isHidden = true
        inventory.backgroundColor = UIColor(white: 0.10, alpha: 0.97)
        inventory.layer.borderWidth = 2
        inventory.layer.borderColor = UIColor.lightGray.cgColor
        overlay.addSubview(inventory)
        inventory.addSubview(inventoryTitle)
        inventory.addSubview(playerHead)
        playerHead.layer.magnificationFilter = .nearest
        playerHead.contentMode = .scaleAspectFit
        inventory.isHidden = true
        grid.register(UICollectionViewCell.self, forCellWithReuseIdentifier: "slot")
        grid.backgroundColor = .clear
        grid.dataSource = self
        grid.delegate = self
        inventory.addSubview(grid)
        configureField(search, placeholder: "Buscar item no criativo")
        search.addTarget(self, action: #selector(filterItems), for: .editingChanged)
        inventory.addSubview(search)
        categoryButton.setTitle("Buscar", for: .normal)
        categoryButton.showsMenuAsPrimaryAction = true
        categoryButton.menu = UIMenu(children: Self.categoryNames.enumerated().map { index, name in
            UIAction(title: name) { [weak self] _ in
                guard let self else { return }; self.selectedCategory = index
                self.categoryButton.setTitle(name, for: .normal); self.filterItems()
            }
        })
        inventory.addSubview(categoryButton)
        let actions: [(String, () -> Void)] = [
            ("Fechar", { [weak self] in self?.closeInventory() }),
            ("Direito", { [weak self] in self?.rightClick.toggle(); self?.updateInventoryTitle() }),
            ("Shift", { [weak self] in self?.shiftClick.toggle(); self?.updateInventoryTitle() }),
            ("Soltar", { [weak self] in self?.clickSlot(255) }),
            ("Criativo", { [weak self] in guard let self, self.creative else { return }; self.creativeCatalog.toggle(); self.filterItems(); self.layout() })
        ]
        for (label, action) in actions { let button = NHActionButton(label, action: action); inventory.addSubview(button); inventoryControls.append(button) }
        let gesture = UILongPressGestureRecognizer(target: self, action: #selector(longPressSlot(_:)))
        grid.addGestureRecognizer(gesture)
        padPanel.backgroundColor = UIColor(white: 0.1, alpha: 1)
        overlay.addSubview(padPanel)
        padPanel.isHidden = true
        padPanel.addSubview(padViewport)
        configureField(padAddress, placeholder: "Endereço")
        padAddress.keyboardType = .URL
        padPanel.addSubview(padAddress)
        padPanel.addSubview(padTabScroll)
        padTabScroll.addSubview(padTabs)
        padTabScroll.showsHorizontalScrollIndicator = true
        padTabs.addTarget(self, action: #selector(selectPadTab), for: .valueChanged)
        for (label, code) in [("‹",6),("›",7),("↻",8),("Início",0),("+ Aba",1),("− Aba",3),("Desligar",4),("Fechar",5)] {
            let button = NHActionButton(label) { [weak self] in
                guard let self else { return }
                if code >= 6 { self.padNavigation?(self.padID, code) }
                else { self.padAction(code, value: code < 2 ? "mod://webdisplays/main.html" : "") }
            }
            padPanel.addSubview(button); padControls.append(button)
        }
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(rotated), name: UIDevice.orientationDidChangeNotification, object: nil)
    }

    private func configureField(_ field: UITextField, placeholder: String) {
        field.delegate = self
        field.borderStyle = .roundedRect
        field.backgroundColor = .secondarySystemBackground
        field.placeholder = placeholder
        field.autocorrectionType = .no
        field.autocapitalizationType = .none
        field.returnKeyType = .send
    }
    func attach(to root: UIView) {
        let changed = overlay.superview !== root || overlay.frame != root.bounds
        if overlay.superview !== root { overlay.removeFromSuperview(); root.addSubview(overlay) }
        overlay.frame = root.bounds
        root.bringSubviewToFront(overlay)
        if changed { layout() }
    }
    private func key(_ code: Int32) {
        typealias SendKey = @convention(c) (Int32,Int32,Int32,Int32) -> Void
        guard let handle = dlopen(nil, RTLD_NOW), let symbol = dlsym(handle,"CallbackBridge_nativeSendKey") else { return }
        let send = unsafeBitCast(symbol, to: SendKey.self)
        send(code,0,1,0)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.06) { send(code,0,0,0) }
    }
    @objc private func rotated() { DispatchQueue.main.async { [weak self] in self?.layout() } }
    @objc private func keyboardChanged(_ notification: Notification) {
        if let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect {
            keyboardHeight = max(0, overlay.bounds.maxY - overlay.convert(frame, from: nil).minY)
            layout()
        }
    }
    private func layout() {
        let safe = overlay.bounds.inset(by: overlay.safeAreaInsets)
        let width = safe.width, height = max(120, safe.height - keyboardHeight)
        toolbar.frame = CGRect(x:safe.minX+8,y:safe.minY+4,width:min(430,width-16),height:32)
        let barWidth = min(440,width*0.65)
        hotbar.frame = CGRect(x:safe.midX-barWidth/2,y:safe.maxY-46,width:barWidth,height:42)
        status.frame = CGRect(x:safe.midX-barWidth/2,y:hotbar.frame.minY-36,width:barWidth,height:36)
        chat.frame = CGRect(x:safe.minX+8,y:safe.minY+height-180,width:width*0.52,height:110)
        input.frame = CGRect(x:safe.minX+8,y:safe.minY+height-44,width:width-16,height:40)
        effects.frame = CGRect(x:safe.maxX-190,y:safe.minY+45,width:182,height:95)
        sidebar.frame = CGRect(x:safe.maxX-215,y:safe.midY-30,width:207,height:180)
        debug.frame = CGRect(x:safe.minX+8,y:safe.minY+40,width:width*0.6,height:height-48)
        playerList.frame = CGRect(x:safe.midX-width*0.3,y:safe.minY+42,width:width*0.6,height:height-90)
        title.frame = CGRect(x:safe.minX+20,y:safe.midY-60,width:width-40,height:100)
        actionBar.frame = CGRect(x:safe.minX+20,y:hotbar.frame.minY-65,width:width-40,height:30)
        bosses.frame = CGRect(x:safe.midX-180,y:safe.minY+40,width:360,height:90)
        respawn.frame = CGRect(x:safe.midX-90,y:safe.midY+35,width:180,height:46)
        leaveRide.frame = CGRect(x:safe.midX-105,y:hotbar.frame.minY-102,width:210,height:32)
        inventory.frame = CGRect(x:safe.minX+12,y:safe.minY+4,width:width-24,height:height-8)
        let iw = inventory.bounds.width, ih = inventory.bounds.height
        playerHead.frame = CGRect(x:8,y:4,width:28,height:28)
        inventoryTitle.frame = CGRect(x:42,y:4,width:iw-50,height:28)
        for (i, control) in inventoryControls.enumerated() { control.frame = CGRect(x:8+CGFloat(i)*(iw-16)/5,y:32,width:(iw-16)/5-3,height:32) }
        search.isHidden = !creativeCatalog
        categoryButton.isHidden = !creativeCatalog
        categoryButton.frame = CGRect(x:8,y:68,width:170,height:34)
        search.frame = CGRect(x:182,y:68,width:max(80,iw-190),height:34)
        let top: CGFloat = creativeCatalog ? 106 : 68
        grid.frame = CGRect(x:8,y:top,width:iw-16,height:max(50,ih-top-8))
        grid.collectionViewLayout.invalidateLayout()
        padPanel.frame = CGRect(x:safe.minX+6,y:safe.minY+2,width:width-12,height:height-4)
        let pw = padPanel.bounds.width
        for (i, control) in padControls.enumerated() { control.frame = CGRect(x:CGFloat(i)*pw/8,y:0,width:pw/8-2,height:32) }
        padAddress.frame = CGRect(x:4,y:36,width:pw-8,height:34)
        padTabScroll.frame = CGRect(x:4,y:74,width:pw-8,height:28)
        let tabsWidth = max(pw-8, CGFloat(padTabs.numberOfSegments)*120)
        padTabs.frame = CGRect(x:0,y:0,width:tabsWidth,height:28)
        padTabScroll.contentSize = padTabs.bounds.size
        padViewport.frame = CGRect(x:4,y:106,width:pw-8,height:max(1,padPanel.bounds.height-110))
        padLayout?()
    }
    func handle(_ operation: String, _ args: [String]) -> Bool {
        func arg(_ index: Int) -> String { args.indices.contains(index) ? args[index] : "" }
        switch operation {
        case "__nh_inventory_show":
            readInventory(arg(0)); inventory.isHidden = false; overlay.bringSubviewToFront(inventory); layout()
        case "__nh_inventory_hide": inventory.isHidden = true; search.resignFirstResponder()
        case "__nh_hotbar_update": readInventory(arg(0)); hotbar.isHidden = hudHidden
        case "__nh_hotbar_hide": hotbar.isHidden = true
        case "__nh_chat_show": input.text = arg(0); input.isHidden = false; overlay.bringSubviewToFront(input); input.becomeFirstResponder()
        case "__nh_chat_hide": hideChat()
        case "__nh_chat_reset": hideChat(); messages.removeAll(); chat.text = ""; overlay.removeFromSuperview()
        case "__nh_chat_message":
            messages.append(Self.text(arg(0))); if messages.count > 100 { messages.removeFirst(messages.count-100) }; chat.text = messages.suffix(6).joined(separator:"\n")
        case "__nh_actionbar": actionBar.text = Self.text(arg(0))
        case "__nh_effects":
            effects.text = arg(0).split(separator:"\n").map { line in
                let fields = line.split(separator:",").map(String.init)
                guard fields.count >= 3 else { return "" }
                let id = Int(fields[0]) ?? 0, ticks = Int(fields[2]) ?? 0
                return "\(Self.effectNames.indices.contains(id) ? Self.effectNames[id] : fields[0]) \((Int(fields[1]) ?? 0)+1)  \(ticks < 0 ? "∞" : "\(ticks/20)s")"
            }.joined(separator:"\n")
        case "__nh_combat":
            let values = arg(0).split(separator:",").map { Double($0) ?? 0 }
            if values.count >= 13 {
                status.text = "♥ \(Int(values[0]))/\(Int(values[1]))  ◆ \(Int(values[5]))  Comida \(Int(values[2]))  Ar \(Int(values[6]))\nXP \(Int(values[7]))  \(Int(values[8]*100))%  Ataque \(Int(values[9]*100))%"
                status.isHidden = hudHidden || arg(1)=="1" || values[12] != 0
                respawn.isHidden = values[10]==0; respawn.isEnabled = values[11] != 0
            }
        case "__nh_riding": leaveRide.isHidden = arg(0)=="0" || arg(2)=="1"; leaveRide.setTitle(arg(1),for:.normal)
        case "__nh_game_hud": readHud(args)
        case "__nh_player_skin":
            if arg(1).utf8.count == 21848, let pixels = Data(base64Encoded:arg(1)), pixels.count == 16384,
               let provider = CGDataProvider(data:pixels as CFData),
               let skin = CGImage(width:64,height:64,bitsPerComponent:8,bitsPerPixel:32,bytesPerRow:256,
                  space:CGColorSpaceCreateDeviceRGB(),bitmapInfo:CGBitmapInfo(rawValue:CGImageAlphaInfo.first.rawValue).union(.byteOrder32Big),
                  provider:provider,decode:nil,shouldInterpolate:false,intent:.defaultIntent),
               let face = skin.cropping(to:CGRect(x:8,y:8,width:8,height:8)) {
                if skinLayers.count >= 64, skinLayers[arg(0)] == nil,
                   let oldest = skinLayers.keys.first(where: { $0 != localPlayerID }) { skinLayers.removeValue(forKey:oldest) }
                skinLayers[arg(0)] = UIImage(cgImage:face)
                playerHead.image = skinLayers[localPlayerID]
            }
        case "__nh_toggle_diagnostic_hud": debug.isHidden.toggle()
        default: return false
        }
        return true
    }
    private func readHud(_ args: [String]) {
        guard args.count >= 5, let data = args[0].data(using:.utf8), let state = (try? JSONSerialization.jsonObject(with:data)) as? [String:Any] else { return }
        hudHidden = args[3]=="1"
        localPlayerID = state["local"] as? String ?? ""
        playerHead.image = skinLayers[localPlayerID]
        hotbar.isHidden = hudHidden || args[4]=="1"
        for label in [effects,sidebar,bosses,actionBar,status,chat] { label.isHidden = hudHidden }
        debug.text = args[1]; debug.isHidden = args[1].isEmpty || hudHidden
        title.text = Self.text(state["title"]) + "\n" + Self.text(state["subtitle"])
        title.alpha = CGFloat((state["titleAlpha"] as? NSNumber)?.doubleValue ?? 0)
        let scores = state["scores"] as? [[String:Any]] ?? []
        sidebar.text = Self.text(state["sidebarTitle"]) + "\n" + scores.map { Self.text($0["name"]) + "  \(($0["value"] as? NSNumber)?.stringValue ?? "")" }.joined(separator:"\n")
        bosses.text = (state["bosses"] as? [[String:Any]] ?? []).map { Self.text($0["name"]) + "  \(Int((($0["progress"] as? NSNumber)?.doubleValue ?? 0)*100))%" }.joined(separator:"\n")
        playerList.isHidden = args[2] != "1" || hudHidden
        playerList.text = Self.text(state["header"]) + "\n" + (state["players"] as? [[String:Any]] ?? []).map { Self.text($0["name"]) + "  \(($0["ping"] as? NSNumber)?.stringValue ?? "") ms" }.joined(separator:"\n") + "\n" + Self.text(state["footer"])
    }
    private func readInventory(_ encoded: String) {
        guard encoded != lastInventory else { return }; lastInventory = encoded
        let lines = encoded.components(separatedBy:"\n")
        let header = (lines.first ?? "").components(separatedBy:"\t")
        selected = Int(header.first ?? "0") ?? 0
        creative = header.count > 4 && header[4]=="1"
        if !creative { creativeCatalog = false }
        for line in lines.dropFirst() {
            let fields = line.components(separatedBy:"\t")
            if fields.count >= 4, let index = Int(fields[0]), slots.indices.contains(index) { slots[index] = (Int(fields[1]) ?? 0,fields[2],fields[3]) }
        }
        for (index, view) in hotbar.arrangedSubviews.enumerated() {
            guard let button = view as? UIButton else { continue }
            let slot = slots[index]; button.setTitle(slot.amount > 0 ? "\(slot.amount)" : "\(index+1)",for:.normal)
            button.setImage(icon(slot.material),for:.normal)
            button.imageView?.contentMode = .scaleAspectFit
            button.imageView?.layer.magnificationFilter = .nearest
            button.layer.borderColor = (index==selected ? UIColor.white : UIColor.gray).cgColor
            button.layer.borderWidth = index==selected ? 3 : 1
            button.accessibilityLabel = slot.name.isEmpty ? slot.material : slot.name
        }
        updateInventoryTitle()
        if header.count > 3, (Int(header[1]) ?? 0)>0 { inventoryTitle.text = (inventoryTitle.text ?? "") + "  Cursor: \(header[3]) ×\(header[1])" }
        grid.reloadData()
    }
    private func updateInventoryTitle() { inventoryTitle.text = "Inventário — \(rightClick ? "botão direito" : "botão esquerdo")\(shiftClick ? " + Shift" : "")" }
    private func clickSlot(_ index: Int) { emit(-1,"nh-inventory-click","\(index),\(rightClick ? 1 : 0),\(shiftClick ? 1 : 0)") }
    private func closeInventory() { emit(-1,"nh-creative-cursor","0"); emit(-1,"nh-inventory-close",""); inventory.isHidden = true; search.resignFirstResponder() }
    @objc private func longPressSlot(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began, !creativeCatalog, let path = grid.indexPathForItem(at:gesture.location(in:grid)) else { return }
        emit(-1,"nh-inventory-click","\(path.item),1,0")
    }
    @objc private func filterItems() {
        if catalog.isEmpty, let url = Bundle.main.url(forResource:"creative_inventory_1_20_1",withExtension:"tsv",subdirectory:"newhorizon/thin-ui/assets/newhorizon"), let text = try? String(contentsOf:url,encoding:.utf8) {
            for line in text.components(separatedBy: .newlines) {
                let fields = line.components(separatedBy:"\t")
                if fields.count >= 7, fields[0] == "v", let variant = Int(fields[1]), let id = Int(fields[2]), let maximum = Int(fields[3]) {
                    catalog.append(CreativeItem(variant:variant,id:id,maximum:maximum,material:fields[4],name:fields[5],nbt:fields[6]))
                } else if fields.count >= 3, fields[0] == "c", let category = Int(fields[1]), let variant = Int(fields[2]) {
                    categories[category,default:[]].insert(variant)
                }
            }
        }
        let query = (search.text ?? "").lowercased()
        filtered = catalog.filter { item in
            (categories[selectedCategory]?.contains(item.variant) ?? false) &&
            (query.isEmpty || item.material.lowercased().contains(query) || item.name.lowercased().contains(query))
        }
        grid.reloadData()
    }
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int { creativeCatalog ? filtered.count : slots.count }
    func collectionView(_ collectionView: UICollectionView, layout collectionViewLayout: UICollectionViewLayout, sizeForItemAt indexPath: IndexPath) -> CGSize { let size=max(32,floor((grid.bounds.width-24)/9)); return CGSize(width:size,height:size) }
    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell=collectionView.dequeueReusableCell(withReuseIdentifier:"slot",for:indexPath)
        cell.contentView.subviews.forEach { $0.removeFromSuperview() }
        cell.backgroundColor=UIColor(white:0.3,alpha:1)
        let material = creativeCatalog ? filtered[indexPath.item].material : slots[indexPath.item].material
        let amount = creativeCatalog ? filtered[indexPath.item].maximum : slots[indexPath.item].amount
        let image=UIImageView(image:icon(material)); image.contentMode = .scaleAspectFit; image.layer.magnificationFilter = .nearest
        image.frame=cell.bounds.insetBy(dx:5,dy:5); image.autoresizingMask=[.flexibleWidth,.flexibleHeight]; cell.contentView.addSubview(image)
        let label=UILabel(frame:cell.bounds); label.autoresizingMask=[.flexibleWidth,.flexibleHeight]; label.textColor = .white; label.font = .systemFont(ofSize:10); label.numberOfLines=3; label.textAlignment = .right
        label.text = amount>0 ? (image.image==nil ? "\(material.replacingOccurrences(of:"minecraft:",with:""))\n×\(amount)" : "\(amount)") : "\(indexPath.item)"
        cell.contentView.addSubview(label); cell.accessibilityLabel=material; return cell
    }
    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        if creativeCatalog { let item=filtered[indexPath.item]; emit(-1,"nh-creative-set-slot","\(36+selected),\(item.id),\(rightClick ? 1 : item.maximum),\(item.material),\(item.nbt)") }
        else { clickSlot(indexPath.item) }
    }
    private func icon(_ material: String) -> UIImage? {
        let name=material.replacingOccurrences(of:"minecraft:",with:"").lowercased()
        guard !name.isEmpty, !name.contains("/"), !name.contains("..") else { return nil }
        if let cached=images.object(forKey:name as NSString) { return cached }
        for category in ["item","block"] {
            let path=Bundle.main.bundlePath+"/newhorizon/thin-ui/assets/minecraft/textures/\(category)/\(name).png"
            if let result=UIImage(contentsOfFile:path) { images.setObject(result,forKey:name as NSString,cost:Int(result.size.width*result.size.height*4)); return result }
        }
        return nil
    }
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        if textField===input { let message=input.text ?? ""; hideChat(); emit(-1,message.isEmpty ? "nh-chat-close" : "nh-chat-send",message) }
        else if textField===padAddress { padAction(0,value:padAddress.text ?? ""); padAddress.resignFirstResponder() }
        else { textField.resignFirstResponder() }
        return true
    }
    private func hideChat() { input.resignFirstResponder(); input.text=""; input.isHidden=true }
    func showPad(id: Int32, url: String, selected: Int, urls: [String], editing: Bool) {
        padID=id; padPanel.isHidden=false; overlay.bringSubviewToFront(padPanel)
        if !padAddress.isFirstResponder { padAddress.text=url }
        padTabs.removeAllSegments()
        for (i, value) in urls.prefix(64).enumerated() { padTabs.insertSegment(withTitle:"\(i+1): \(URL(string:value)?.host ?? "Início")",at:i,animated:false) }
        padTabs.selectedSegmentIndex = (0..<padTabs.numberOfSegments).contains(selected) ? selected : UISegmentedControl.noSegment
        layout()
        if padTabs.selectedSegmentIndex != UISegmentedControl.noSegment {
            let tabWidth = padTabs.bounds.width / CGFloat(padTabs.numberOfSegments)
            padTabScroll.scrollRectToVisible(CGRect(x:CGFloat(selected)*tabWidth,y:0,width:tabWidth,height:28),animated:false)
        }
        if editing { padAddress.becomeFirstResponder(); padAddress.selectAll(nil) }
    }
    func hidePad() { padAddress.resignFirstResponder(); padPanel.isHidden=true; padID = -1 }
    @objc private func selectPadTab() { padAction(2) }
    private func padAction(_ action: Int, value: String = "") { emit(padID,"nh-pad-action","\(action)\n\(padTabs.selectedSegmentIndex)\n\(value)") }
    static func text(_ value: Any?) -> String {
        if let string=value as? String {
            if let data=string.data(using:.utf8), let json=try? JSONSerialization.jsonObject(with:data,options:.fragmentsAllowed), !(json is String) { return text(json) }
            if string.hasPrefix("\""), let data=string.data(using:.utf8), let decoded=(try? JSONSerialization.jsonObject(with:data,options:.fragmentsAllowed)) as? String { return decoded }
            return string
        }
        if let object=value as? [String:Any] { return (object["text"] as? String ?? object["translate"] as? String ?? "") + text(object["with"]) + text(object["extra"]) }
        if let array=value as? [Any] { return array.map { text($0) }.joined() }
        return ""
    }
    private static let effectNames=["","Velocidade","Lentidão","Pressa","Fadiga","Força","Cura","Dano","Salto","Náusea","Regeneração","Resistência","Resistência ao fogo","Respiração","Invisibilidade","Cegueira","Visão noturna","Fome","Fraqueza","Veneno","Wither","Vida extra","Absorção","Saturação","Brilho","Levitação","Sorte","Azar","Queda lenta","Poder do canal","Golfinho","Mau presságio","Herói da vila","Escuridão"]
    private static let categoryNames=["Construção","Coloridos","Naturais","Funcionais","Redstone","Barras salvas","Buscar","Ferramentas","Combate","Comidas e bebidas","Ingredientes","Ovos geradores","Operador","Inventário"]
}
