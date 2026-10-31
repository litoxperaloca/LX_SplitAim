--[[
    LX_SplitAim - opciones (Menu principal -> Opciones -> MODS -> "LX Split Aim").
    Usa PZAPI.ModOptions (B42). Los valores se guardan solos.
    Los ajustes valen para TODOS los jugadores; el estado de apuntado es por jugador.
]]

if not PZAPI or not PZAPI.ModOptions then return end

local opts = PZAPI.ModOptions:create("LXSplitAim", getText("UI_LXSA_name"))
opts:addDescription(getText("UI_LXSA_desc"))
opts:addSeparator()

local tEnable  = opts:addTickBox("ENABLED", getText("UI_LXSA_enable"), true, getText("UI_LXSA_enable_tip"))
opts:addSeparator()

-- Lock-on estilo B41
local tLock    = opts:addTickBox("LOCK_ON", getText("UI_LXSA_lock"), true, getText("UI_LXSA_lock_tip"))
local sCone    = opts:addSlider("LOCK_CONE", getText("UI_LXSA_cone"), 10, 50, 1, 28, getText("UI_LXSA_cone_tip"))
local sSnap    = opts:addSlider("LOCK_SNAP", getText("UI_LXSA_snap"), 0.10, 1.00, 0.05, 0.35, getText("UI_LXSA_snap_tip"))
local sLevels  = opts:addSlider("LOCK_LEVELS", getText("UI_LXSA_levels"), 0, 3, 1, 3, getText("UI_LXSA_levels_tip"))
local tHead    = opts:addTickBox("LOCK_HEAD", getText("UI_LXSA_head"), false, getText("UI_LXSA_head_tip"))
local tMarker  = opts:addTickBox("LOCK_MARKER", getText("UI_LXSA_marker"), true, getText("UI_LXSA_marker_tip"))
opts:addSeparator()

-- Soft-snap (cuando no hay lock o el lock esta apagado)
local sSX  = opts:addSlider("ASSIST_X", getText("UI_LXSA_assist_x"), 0.00, 1.00, 0.05, 0.65, getText("UI_LXSA_assist_tip"))
local sSY  = opts:addSlider("ASSIST_Y", getText("UI_LXSA_assist_y"), 0.00, 1.00, 0.05, 0.75, getText("UI_LXSA_assist_tip"))
local sSH  = opts:addSlider("ASSIST_HEAD", getText("UI_LXSA_assist_head"), 0.00, 1.00, 0.05, 0.95, getText("UI_LXSA_assist_tip"))
local sUR  = opts:addSlider("UP_RESIST", getText("UI_LXSA_up"), 0.00, 1.00, 0.05, 0.70, getText("UI_LXSA_up_tip"))
local sTrk = opts:addSlider("TRACK", getText("UI_LXSA_track"), 0.00, 1.00, 0.05, 1.00, getText("UI_LXSA_track_tip"))
local sHBW = opts:addSlider("HITBOX_WIDTH", getText("UI_LXSA_body_w"), 0.10, 2.00, 0.05, 0.40, getText("UI_LXSA_zone_tip"))
local sHBH = opts:addSlider("HITBOX_HEIGHT", getText("UI_LXSA_body_h"), 0.10, 2.00, 0.05, 0.45, getText("UI_LXSA_zone_tip"))
local sHBD = opts:addSlider("HITBOX_DOWN", getText("UI_LXSA_body_d"), 0.00, 1.00, 0.05, 0.15, getText("UI_LXSA_zone_tip"))
local sHW  = opts:addSlider("HEAD_W", getText("UI_LXSA_head_w"), 0.00, 0.50, 0.01, 0.05, getText("UI_LXSA_zone_tip"))
local sHH  = opts:addSlider("HEAD_H", getText("UI_LXSA_head_h"), 0.00, 0.50, 0.01, 0.05, getText("UI_LXSA_zone_tip"))
opts:addSeparator()

local sRec   = opts:addSlider("RECENTER_RESIST", getText("UI_LXSA_recenter"), 0.00, 0.90, 0.05, 0.90, getText("UI_LXSA_recenter_tip"))
local sCurve = opts:addSlider("CURVE", getText("UI_LXSA_curve"), 0.50, 3.00, 0.05, 1.00, getText("UI_LXSA_curve_tip"))

local dependents = { tLock, sCone, sSnap, sLevels, tHead, tMarker, sSX, sSY, sSH, sUR, sTrk,
                     sHBW, sHBH, sHBD, sHW, sHH, sRec, sCurve }

local function applyEnabledState(masterOverride)
    local on
    if masterOverride ~= nil then on = (masterOverride == true) else on = (tEnable:getValue() == true) end
    for _, o in ipairs(dependents) do
        if o and o.setEnabled then pcall(o.setEnabled, o, on) end
    end
end

tEnable.onChange = function(self, value) applyEnabledState(value) end

local function push(name, v)
    local f = _G[name]
    if type(f) == "function" then pcall(f, v) end
end

opts.apply = function(self)
    push("lxsa_setApplyOverride", tEnable:getValue() == true)
    push("lxsa_setLockOn", tLock:getValue() == true)
    push("lxsa_setLockCone", sCone:getValue())
    push("lxsa_setLockSnap", sSnap:getValue())
    local lv = sLevels:getValue()
    push("lxsa_setLockMaxLevels", (lv >= 3) and 3.3 or (lv + 0.3))
    push("lxsa_setLockHead", tHead:getValue() == true)
    push("lxsa_setShowLockMarker", tMarker:getValue() == true)
    -- el slider es "fuerza"; Java espera "factor de frenado" (1 - fuerza)
    push("lxsa_setSlowdownX", 1.0 - sSX:getValue())
    push("lxsa_setSlowdownY", 1.0 - sSY:getValue())
    push("lxsa_setSlowdownHead", 1.0 - sSH:getValue())
    push("lxsa_setUpResist", sUR:getValue())
    push("lxsa_setTrackingFactor", sTrk:getValue())
    push("lxsa_setHitboxWidth", sHBW:getValue())
    push("lxsa_setHitboxHeight", sHBH:getValue())
    push("lxsa_setHitboxDown", sHBD:getValue())
    push("lxsa_setHeadHalfW", sHW:getValue())
    push("lxsa_setHeadHalfH", sHH:getValue())
    push("lxsa_setRecenterResist", sRec:getValue())
    push("lxsa_setStickCurve", sCurve:getValue())
    applyEnabledState()
end

-- PZAPI no llama apply() al cargar: lo forzamos al entrar al menu y al iniciar partida.
Events.OnMainMenuEnter.Add(function() opts:apply() end)
Events.OnGameStart.Add(function() opts:apply() end)
