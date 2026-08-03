String autoBlockModule = "AutoBlock";
String killAuraModule = "KillAura";
String displaceModule = "Displace";
String hitSelectModule = "";
String attackOnDamageSetting = "";
String hitSelectSetting = "";
String rangeSetting = "Disable block out of range";
boolean attackOnDamageIsSlider = false;
boolean rangeResolved = false;
int attackWindow = 5;

int comboTargetId = -1;
int comboHits = 0;
int idleTicks = 0;
int ticksSinceAttack = 999;
int lastTargetHurt = 0;
int lastSelfHurt = 0;
boolean suppressed = false;
boolean savedEnabled = false;
boolean displaceApplied = false;
double savedAttackOnDamageSlider = 0.0;
boolean savedAttackOnDamageButton = false;
boolean savedHitSelect = false;

boolean overridden = false;
boolean forcing = false;
boolean rangeOverrideActive = false;
int repressTicks = 0;

List<Integer> sweepQueue = new ArrayList<>();
int lastSweepSlot = -1;
boolean sweeping = false;

ArrayList<String> teammates = new ArrayList<>();
ArrayList<String> opponents = new ArrayList<>();
HashSet<String> addedFriends = new HashSet<>();
HashSet<String> addedEnemies = new HashSet<>();
HashSet<String> restoreFriends = new HashSet<>();
HashSet<String> restoreEnemies = new HashSet<>();
Pattern namePattern = Pattern.compile("^(\\w{3,16})\\b");
String activeRoster = "";
long lastScan = 0L;
boolean teamsActive = false;

void onLoad() {
    modules.registerDescription("autoblock: ");
    modules.registerSlider("Disable in combo", "+", 2.0, 2.0, 8.0, 1.0);
    modules.registerSlider("Reset Delay", " ticks", 12.0, 4.0, 40.0, 1.0);
    modules.registerButton("Reset When Hit", true);
    modules.registerDescription("utils: ");
    modules.registerButton("Displace Toggle", true);
    modules.registerButton("Allow Blocking Out Of Range", true);
    modules.registerSlider("Inventory Fill", "", 2, new String[] {
        util.color("&cDisabled"), "1 slot/tick", "2 slots/tick", "3 slots/tick", "4 slots/tick"});
    modules.registerButton("Practice Teams", true);
    modules.registerButton("No Pot Delay", true);
}

void onEnable() {
    resolveModules();
    fullReset();
    resetSweep();
    activeRoster = "";
    lastScan = 0L;
    teamsActive = modules.getButton(scriptName, "Practice Teams");
    rangeOverrideActive = modules.getButton(scriptName, "Allow Blocking Out Of Range");
    overridden = false;
    forcing = false;
    repressTicks = 0;
    if (rangeResolved) {
        modules.setButton(autoBlockModule, rangeSetting, true);
    } else if (rangeOverrideActive) {
        client.print(util.color("&cAutoBlock range setting not found."));
    }
}

void onDisable() {
    restore();
    restoreDisplace();
    releaseControl();
    if (rangeResolved && overridden) modules.setButton(autoBlockModule, rangeSetting, true);
    overridden = false;
    clearTags();
    activeRoster = "";
    resetSweep();
    fullReset();
}

void onWorldJoin(Entity entity) {
    if (entity != null && entity.isUser) {
        clearTags();
        activeRoster = "";
    }
}

void onGuiUpdate(String name, boolean opened) {
    resetSweep();
}

void resolveModules() {
    Map<String, List<String>> cats = modules.getCategories();
    if (cats == null) return;
    for (List<String> names : cats.values()) {
        if (names == null) continue;
        for (String n : names) {
            if (n == null) continue;
            String key = n.toLowerCase().replace(" ", "");
            if (key.contains("autoblock")) autoBlockModule = n;
            else if (key.contains("killaura")) killAuraModule = n;
            else if (key.contains("displace")) displaceModule = n;
            else if (key.contains("hitselect")) hitSelectModule = n;
        }
    }
    resolveKillAuraSettings();
    resolveRangeSetting();
}

void resolveKillAuraSettings() {
    Map<String, Object> settings = modules.getSettings(killAuraModule);
    if (settings == null) return;
    for (String key : settings.keySet()) {
        if (key == null) continue;
        String k = key.toLowerCase().replace(" ", "");
        if (k.contains("attackondamage")) {
            attackOnDamageSetting = key;
            attackOnDamageIsSlider = modules.getSliderMax(killAuraModule, key) > 0.0;
        } else if (k.contains("hitselect")) {
            hitSelectSetting = key;
        }
    }
}

void resolveRangeSetting() {
    rangeResolved = false;
    Map<String, Object> settings = modules.getSettings(autoBlockModule);
    if (settings == null) return;
    for (String key : settings.keySet()) {
        if (key == null) continue;
        if (key.toLowerCase().replace(" ", "").contains("outofrange")) {
            rangeSetting = key;
            rangeResolved = true;
            return;
        }
    }
}

boolean onPacketSent(CPacket packet) {
    if (packet instanceof C02) {
        C02 c02 = (C02) packet;
        if ("ATTACK".equals(c02.action) && c02.entity != null && c02.entity.isPlayer && !c02.entity.isUser) {
            if (c02.entity.entityId != comboTargetId) {
                comboTargetId = c02.entity.entityId;
                lastTargetHurt = 0;
                comboHits = 0;
                idleTicks = 0;
            }
            ticksSinceAttack = 0;
        }
    }
    return true;
}

void onPreMotion(PlayerState state) {
    Entity self = client.getPlayer();
    if (self == null) {
        restore();
        restoreDisplace();
        fullReset();
        return;
    }

    boolean displaceOn = modules.getButton(scriptName, "Displace Toggle") && modules.isEnabled(displaceModule);
    if (displaceOn && !displaceApplied) {
        applyDisplace();
    } else if (!displaceOn && displaceApplied) {
        restoreDisplace();
    }

    int selfHurt = self.getHurtTime();
    if (selfHurt > lastSelfHurt && selfHurt > 0 && modules.getButton(scriptName, "Reset When Hit")) {
        dropCombo();
    }
    lastSelfHurt = selfHurt;

    if (comboTargetId >= 0) {
        Entity target = world.getEntityById(comboTargetId);
        if (target == null || target.isDead()) {
            dropCombo();
        } else {
            int hurt = target.getHurtTime();
            if (hurt > lastTargetHurt && ticksSinceAttack <= attackWindow) {
                comboHits++;
                idleTicks = 0;
            }
            lastTargetHurt = hurt;
        }
    }

    if (comboHits > 0) {
        idleTicks++;
        if (idleTicks > (int) modules.getSlider(scriptName, "Reset Delay")) {
            dropCombo();
        }
    }

    if (ticksSinceAttack < 999) ticksSinceAttack++;

    boolean inCombo = comboHits >= (int) modules.getSlider(scriptName, "Disable in combo");

    if (inCombo && !suppressed) {
        if (modules.isEnabled(autoBlockModule)) {
            modules.disable(autoBlockModule);
            savedEnabled = true;
        } else {
            savedEnabled = false;
        }
        suppressed = true;
    } else if (!inCombo && suppressed) {
        restore();
    }
}

void onPreUpdate() {
    tickBlockRange();
    tickPotDelay();
    tickSweep();
    tickPracticeTeams();
}

void tickPotDelay() {
    if (!modules.getButton(scriptName, "No Pot Delay")) return;
    if (!keybinds.isMouseDown(1) || guiOpen()) return;

    Entity self = client.getPlayer();
    if (self == null) return;

    ItemStack held = self.getHeldItem();
    if (held == null || !isSplashPotion(held)) return;

    keybinds.rightClick();
}

boolean isSplashPotion(ItemStack stack) {
    if (stack.meta < 16384) return false;
    String name = stack.name == null ? "" : stack.name.toLowerCase();
    return name.contains("potion");
}

void applyDisplace() {
    displaceApplied = true;
    if (attackOnDamageSetting.length() > 0) {
        if (attackOnDamageIsSlider) {
            savedAttackOnDamageSlider = modules.getSlider(killAuraModule, attackOnDamageSetting);
            double min = modules.getSliderMin(killAuraModule, attackOnDamageSetting);
            modules.setSlider(killAuraModule, attackOnDamageSetting, min > 0.0 ? min : 0.0);
        } else {
            savedAttackOnDamageButton = modules.getButton(killAuraModule, attackOnDamageSetting);
            modules.setButton(killAuraModule, attackOnDamageSetting, false);
        }
    }
    if (hitSelectSetting.length() > 0) {
        savedHitSelect = modules.getButton(killAuraModule, hitSelectSetting);
        modules.setButton(killAuraModule, hitSelectSetting, false);
    } else if (hitSelectModule.length() > 0) {
        savedHitSelect = modules.isEnabled(hitSelectModule);
        if (savedHitSelect) modules.disable(hitSelectModule);
    }
}

void restoreDisplace() {
    if (!displaceApplied) return;
    displaceApplied = false;
    if (attackOnDamageSetting.length() > 0) {
        if (attackOnDamageIsSlider) {
            modules.setSlider(killAuraModule, attackOnDamageSetting, savedAttackOnDamageSlider);
        } else {
            modules.setButton(killAuraModule, attackOnDamageSetting, savedAttackOnDamageButton);
        }
    }
    if (hitSelectSetting.length() > 0) {
        modules.setButton(killAuraModule, hitSelectSetting, savedHitSelect);
    } else if (hitSelectModule.length() > 0 && savedHitSelect) {
        modules.enable(hitSelectModule);
    }
}

void dropCombo() {
    comboHits = 0;
    idleTicks = 0;
    comboTargetId = -1;
    lastTargetHurt = 0;
}

void restore() {
    if (suppressed) {
        if (savedEnabled) modules.enable(autoBlockModule);
        suppressed = false;
        savedEnabled = false;
    }
}

void fullReset() {
    comboHits = 0;
    idleTicks = 0;
    comboTargetId = -1;
    lastTargetHurt = 0;
    lastSelfHurt = 0;
    ticksSinceAttack = 999;
}

void tickBlockRange() {
    boolean on = modules.getButton(scriptName, "Allow Blocking Out Of Range");
    if (!on) {
        if (rangeOverrideActive) {
            releaseControl();
            if (rangeResolved && overridden) modules.setButton(autoBlockModule, rangeSetting, true);
            overridden = false;
            rangeOverrideActive = false;
        }
        return;
    }
    rangeOverrideActive = true;
    if (!rangeResolved) return;

    boolean want = !keybinds.isMouseDown(0);
    if (want != overridden) {
        overridden = want;
        modules.setButton(autoBlockModule, rangeSetting, !want);
        if (want) {
            if (keybinds.isMouseDown(1) && !guiOpen()) {
                repressTicks = 1;
                forcing = true;
            }
        } else {
            releaseControl();
        }
    }
    if (!forcing) return;
    if (!overridden || !keybinds.isMouseDown(1) || guiOpen() || !holdingSword()) {
        releaseControl();
        return;
    }
    if (repressTicks > 0) {
        repressTicks--;
        keybinds.setPressed("use", false);
        return;
    }
    keybinds.setPressed("use", true);
}

boolean guiOpen() {
    String screen = client.getScreen();
    return screen != null && screen.length() > 0;
}

boolean holdingSword() {
    Entity self = client.getPlayer();
    return self != null && self.isHoldingSword();
}

void releaseControl() {
    if (!forcing) return;
    forcing = false;
    repressTicks = 0;
    keybinds.setPressed("use", keybinds.isMouseDown(1) && !guiOpen());
}

void onRenderTick(float partialTicks) {
    if (sweepBurst() < 1) return;

    if (!keybinds.isMouseDown(0) || !shiftDown()) {
        sweeping = false;
        lastSweepSlot = -1;
        return;
    }

    int slot = hoveredSlot();
    if (slot < 0) return;

    if (!sweeping) {
        sweeping = true;
        lastSweepSlot = slot;
        return;
    }

    if (slot == lastSweepSlot) return;
    lastSweepSlot = slot;

    if (sweepQueue.size() < 36 && !sweepQueue.contains(slot)) sweepQueue.add(slot);
}

void tickSweep() {
    int max = sweepBurst();
    if (max < 1 || !shiftDown()) {
        sweepQueue.clear();
        return;
    }
    if (sweepQueue.isEmpty()) return;

    String screen = client.getScreen();
    if (!screen.equals("GuiChest") && !screen.equals("GuiInventory")) {
        sweepQueue.clear();
        return;
    }

    int sent = 0;
    while (sent < max && !sweepQueue.isEmpty()) {
        inventory.click(sweepQueue.remove(0), 0, 1);
        sent++;
    }
}

int sweepBurst() {
    return (int) modules.getSlider(scriptName, "Inventory Fill");
}

void resetSweep() {
    sweepQueue.clear();
    sweeping = false;
    lastSweepSlot = -1;
}

boolean shiftDown() {
    return keybinds.isKeyDown(42) || keybinds.isKeyDown(54);
}

int hoveredSlot() {
    String screen = client.getScreen();
    boolean chest = screen.equals("GuiChest");
    if (!chest && !screen.equals("GuiInventory")) return -1;

    int rows = chest ? inventory.getChestSize() / 9 : 0;
    if (chest && rows < 1) return -1;

    int[] display = client.getDisplaySize();
    int[] mouse = keybinds.getMousePosition();
    int scale = display[2] < 1 ? 1 : display[2];
    int mouseX = mouse[0] / scale;
    int mouseY = display[1] - mouse[1] / scale - 1;

    int ySize = chest ? 114 + rows * 18 : 166;
    int relX = mouseX - (display[0] - 176) / 2;
    int relY = mouseY - (display[1] - ySize) / 2;

    if (chest) {
        int offset = (rows - 4) * 18;
        int slot = gridSlot(relX, relY, 8, 18, 9, rows, 0);
        if (slot >= 0) return slot;
        slot = gridSlot(relX, relY, 8, 103 + offset, 9, 3, rows * 9);
        if (slot >= 0) return slot;
        return gridSlot(relX, relY, 8, 161 + offset, 9, 1, rows * 9 + 27);
    }

    int slot = gridSlot(relX, relY, 154, 28, 1, 1, 0);
    if (slot >= 0) return slot;
    slot = gridSlot(relX, relY, 98, 18, 2, 2, 1);
    if (slot >= 0) return slot;
    slot = gridSlot(relX, relY, 8, 8, 1, 4, 5);
    if (slot >= 0) return slot;
    slot = gridSlot(relX, relY, 8, 84, 9, 3, 9);
    if (slot >= 0) return slot;
    return gridSlot(relX, relY, 8, 142, 9, 1, 36);
}

int gridSlot(int relX, int relY, int x, int y, int cols, int rows, int base) {
    int dx = relX - x + 1;
    int dy = relY - y + 1;
    if (dx < 0 || dy < 0) return -1;
    int col = dx / 18;
    int row = dy / 18;
    if (col >= cols || row >= rows) return -1;
    return base + row * cols + col;
}

void tickPracticeTeams() {
    boolean on = modules.getButton(scriptName, "Practice Teams");
    if (!on) {
        if (teamsActive) {
            clearTags();
            activeRoster = "";
            teamsActive = false;
        }
        return;
    }
    teamsActive = true;

    long now = client.time();
    if (now - lastScan < 500L) return;
    lastScan = now;

    if (!scanScoreboard()) {
        if (activeRoster.length() > 0) {
            clearTags();
            activeRoster = "";
        }
        return;
    }

    String roster = buildRoster();
    if (roster.equals(activeRoster)) return;

    clearTags();
    applyTags();
    activeRoster = roster;
}

boolean scanScoreboard() {
    List<String> lines = world.getScoreboard();
    if (lines == null || lines.isEmpty()) return false;

    teammates.clear();
    opponents.clear();

    int section = 0;
    boolean matchLayout = false;

    for (int index = 0; index < lines.size(); index++) {
        String line = util.strip(lines.get(index)).trim();
        String lower = line.toLowerCase();

        if (lower.startsWith("your team")) {
            section = 1;
            matchLayout = true;
            continue;
        }
        if (lower.startsWith("their team") || lower.startsWith("enemy team") || lower.startsWith("opponent")) {
            section = 2;
            matchLayout = true;
            continue;
        }
        if (section == 0) continue;

        if (line.length() == 0 || lower.startsWith("your ping") || lower.endsWith(":")) {
            section = 0;
            continue;
        }

        String name = extractName(line);
        if (name == null) continue;

        if (section == 1) {
            if (!teammates.contains(name)) teammates.add(name);
        } else if (!opponents.contains(name)) {
            opponents.add(name);
        }
    }

    return matchLayout && (!teammates.isEmpty() || !opponents.isEmpty());
}

String extractName(String line) {
    Matcher matcher = namePattern.matcher(line);
    if (!matcher.find()) return null;

    String name = matcher.group(1);
    String rest = line.substring(name.length()).trim();
    if (rest.length() > 0 && !rest.startsWith("(")) return null;

    return name;
}

String buildRoster() {
    StringBuilder roster = new StringBuilder();
    for (int index = 0; index < teammates.size(); index++) {
        roster.append(teammates.get(index)).append('+');
    }
    roster.append('|');
    for (int index = 0; index < opponents.size(); index++) {
        roster.append(opponents.get(index)).append('-');
    }
    return roster.toString();
}

void applyTags() {
    Entity player = client.getPlayer();
    String self = player == null ? null : util.strip(player.getName());

    for (int index = 0; index < teammates.size(); index++) {
        String name = teammates.get(index);
        if (self != null && name.equalsIgnoreCase(self)) continue;
        if (client.isEnemy(name)) {
            client.removeEnemy(name);
            restoreEnemies.add(name);
        }
        if (!client.isFriend(name)) {
            client.addFriend(name);
            addedFriends.add(name);
        }
    }

    for (int index = 0; index < opponents.size(); index++) {
        String name = opponents.get(index);
        if (self != null && name.equalsIgnoreCase(self)) continue;
        if (client.isFriend(name)) {
            client.removeFriend(name);
            restoreFriends.add(name);
        }
        if (!client.isEnemy(name)) {
            client.addEnemy(name);
            addedEnemies.add(name);
        }
    }
}

void clearTags() {
    Iterator<String> friends = addedFriends.iterator();
    while (friends.hasNext()) {
        client.removeFriend(friends.next());
    }
    addedFriends.clear();

    Iterator<String> enemies = addedEnemies.iterator();
    while (enemies.hasNext()) {
        client.removeEnemy(enemies.next());
    }
    addedEnemies.clear();

    Iterator<String> priorFriends = restoreFriends.iterator();
    while (priorFriends.hasNext()) {
        client.addFriend(priorFriends.next());
    }
    restoreFriends.clear();

    Iterator<String> priorEnemies = restoreEnemies.iterator();
    while (priorEnemies.hasNext()) {
        client.addEnemy(priorEnemies.next());
    }
    restoreEnemies.clear();
}
