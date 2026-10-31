--[[
    LX_SplitAim - bucle principal (Lua).
    Fork split-screen de "[B42] Controller Aim Assist" (MIT, "Here to Suffer not for Fun").

    Por cada jugador local con mando, cada tick decide si el aim assist esta activo y se lo
    comunica a Java CON EL INDICE DEL JUGADOR (el original usaba flags globales, por eso en
    split-screen un jugador pisaba el estado del otro).

    Activo cuando: jugador local vivo + mando asignado + arma de fuego lista + stick derecho
    fuera de la zona muerta + ninguna UI capturando el mando de ESE jugador.
    El apuntado vanilla de B42 sigue requiriendo mantener el gatillo de apuntar (LT).
]]

LXSplitAim = LXSplitAim or {}
local M = LXSplitAim

M.Config = M.Config or { STICK_DEAD_ZONE = 0.18 }
M.active = M.active or {}      -- [playerNum] = true/false
M.javaOk = false

local function javaFn(name)
    local f = _G[name]
    if type(f) == "function" then return f end
    return nil
end

local function callJava(name, ...)
    local f = javaFn(name)
    if not f then return nil end
    local ok, res = pcall(f, ...)
    if ok then return res end
    return nil
end

local function isFirearm(weapon)
    if not weapon or not instanceof(weapon, "HandWeapon") then return false end
    local ok, v = pcall(weapon.isRanged, weapon)
    if ok and v then return true end
    if weapon.isAimedFirearm then
        ok, v = pcall(weapon.isAimedFirearm, weapon)
        if ok and v then return true end
    end
    return false
end

local function joypadId(playerNum)
    if not getJoypadData then return nil end
    local data = getJoypadData(playerNum)
    if not data or not data.id or data.id < 0 then return nil end
    return data.id
end

local function rightStick(id)
    local x = (getJoypadAimingAxisX and getJoypadAimingAxisX(id)) or 0
    local y = (getJoypadAimingAxisY and getJoypadAimingAxisY(id)) or 0
    return x, y
end

-- UI capturando el mando de ESTE jugador (radial, inventario, mapa, menus...)
local function uiCapturing(playerNum)
    local jd = getJoypadData and getJoypadData(playerNum)
    if jd and jd.focus ~= nil then return true end
    if getPlayerRadialMenu then
        local ok, radial = pcall(getPlayerRadialMenu, playerNum)
        if ok and radial and radial.isReallyVisible then
            local ok2, vis = pcall(radial.isReallyVisible, radial)
            if ok2 and vis == true then return true end
        end
    end
    if ISWorldMap and ISWorldMap.instance ~= nil then
        local owner = ISWorldMap.instance.playerNum
        if owner == nil or owner == playerNum then return true end
    end
    return false
end

-- Solo el jugador 0 puede ser el de teclado/mouse.
local function mouseInUse(playerNum)
    if playerNum ~= 0 then return false end
    if wasMouseActiveMoreRecentlyThanJoypad then
        local ok, v = pcall(wasMouseActiveMoreRecentlyThanJoypad)
        if ok then return v == true end
    end
    return false
end

local function setActive(player, playerNum, on)
    local was = M.active[playerNum] == true
    M.active[playerNum] = on
    callJava("lxsa_setAimGranted", playerNum, on)
    if on then
        pcall(player.setIsAiming, player, true)
    elseif was then
        -- solo en la transicion: no pisamos el apuntado vanilla (LT) en cada tick
        pcall(player.setIsAiming, player, false)
    end
end

local function onPlayerUpdate(player)
    if not M.javaOk or not player then return end
    local okLocal, isLocal = pcall(player.isLocalPlayer, player)
    if not okLocal or not isLocal then return end
    local playerNum = player:getPlayerNum()
    if playerNum == nil or playerNum < 0 or playerNum > 3 then return end

    local id = joypadId(playerNum)
    if not id then
        callJava("lxsa_setSuppressed", playerNum, false)
        if M.active[playerNum] then setActive(player, playerNum, false) end
        return
    end

    local capturing = uiCapturing(playerNum)
    callJava("lxsa_setSuppressed", playerNum, capturing)
    if capturing or player:isDead() or mouseInUse(playerNum) then
        if M.active[playerNum] then setActive(player, playerNum, false) end
        return
    end

    if not isFirearm(player:getPrimaryHandItem()) then
        if M.active[playerNum] then setActive(player, playerNum, false) end
        return
    end
    if player.isWeaponReady then
        local ok, ready = pcall(player.isWeaponReady, player)
        if ok and ready == false then
            if M.active[playerNum] then setActive(player, playerNum, false) end
            return
        end
    end

    local sx, sy = rightStick(id)
    if math.sqrt(sx * sx + sy * sy) < M.Config.STICK_DEAD_ZONE then
        if M.active[playerNum] then setActive(player, playerNum, false) end
        return
    end
    setActive(player, playerNum, true)
end

local function resetAll()
    for i = 0, 3 do
        M.active[i] = false
        callJava("lxsa_resetPlayer", i)
        callJava("lxsa_setAimGranted", i, false)
        callJava("lxsa_setSuppressed", i, false)
    end
end

local function onGameStart()
    M.javaOk = javaFn("lxsa_isLoaded") ~= nil
    if not M.javaOk then
        print("[LX_SplitAim] AVISO: la parte Java no esta cargada (ZombieBuddy no aprobo/cargo LX_SplitAim). El aim assist queda inactivo.")
    else
        print("[LX_SplitAim] Java OK - aim assist por jugador activo.")
    end
    resetAll()
end

local function onPlayerDeath(player)
    if not player or not player.getPlayerNum then return end
    local n = player:getPlayerNum()
    if n and n >= 0 and n <= 3 then
        M.active[n] = false
        callJava("lxsa_resetPlayer", n)
        callJava("lxsa_setAimGranted", n, false)
    end
end

Events.OnGameStart.Add(onGameStart)
Events.OnPlayerUpdate.Add(onPlayerUpdate)
Events.OnPlayerDeath.Add(onPlayerDeath)
