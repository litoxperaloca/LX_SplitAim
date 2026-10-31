--[[
    LX_SplitAim - marcador del blanco enganchado (lock-on), uno por jugador, en su viewport.
    El elemento de UI solo se agrega al UIManager mientras algun jugador tiene lock y la
    opcion esta activa; isMouseOver() siempre false para no tragarse clicks.
]]

if not ISUIElement then return end

local Marker = ISUIElement:derive("LXSplitAim_Marker")

function Marker:new()
    local core = getCore()
    local o = ISUIElement.new(self, 0, 0, core:getScreenWidth(), core:getScreenHeight())
    o:setWantKeyEvents(false)
    return o
end

function Marker:isMouseOver() return false end

local SIZE, GAP = 9, 4

function Marker:render()
    if not lxsa_isLocked then return end
    for p = 0, 3 do
        if lxsa_isLocked(p) then
            local x = lxsa_getLockScreenX(p)
            local y = lxsa_getLockScreenY(p)
            if x == x and y == y then -- no NaN
                local ox = (getPlayerScreenLeft and getPlayerScreenLeft(p)) or 0
                local oy = (getPlayerScreenTop and getPlayerScreenTop(p)) or 0
                x, y = x + ox, y + oy
                local a, r, g, b = 0.85, 1.0, 0.25, 0.20
                -- cuatro esquinas tipo "brackets"
                self:drawRect(x - SIZE - GAP, y - SIZE - GAP, SIZE, 2, a, r, g, b)
                self:drawRect(x - SIZE - GAP, y - SIZE - GAP, 2, SIZE, a, r, g, b)
                self:drawRect(x + GAP, y - SIZE - GAP, SIZE, 2, a, r, g, b)
                self:drawRect(x + GAP + SIZE - 2, y - SIZE - GAP, 2, SIZE, a, r, g, b)
                self:drawRect(x - SIZE - GAP, y + SIZE + GAP - 2, SIZE, 2, a, r, g, b)
                self:drawRect(x - SIZE - GAP, y + GAP, 2, SIZE, a, r, g, b)
                self:drawRect(x + GAP, y + SIZE + GAP - 2, SIZE, 2, a, r, g, b)
                self:drawRect(x + GAP + SIZE - 2, y + GAP, 2, SIZE, a, r, g, b)
            end
        end
    end
end

local instance, added = nil, false

local function wanted()
    if not lxsa_getShowLockMarker or not lxsa_isLocked then return false end
    local ok, show = pcall(lxsa_getShowLockMarker)
    if not ok or not show then return false end
    for p = 0, 3 do
        if lxsa_isLocked(p) then return true end
    end
    return false
end

local function sync()
    local want = wanted()
    if want and not added then
        if not instance then
            instance = Marker:new()
            instance:initialise()
            instance:instantiate()
            instance:setAlwaysOnTop(false)
        end
        instance:addToUIManager()
        added = true
    elseif not want and added then
        instance:removeFromUIManager()
        added = false
    end
end

Events.OnTick.Add(function()
    local ok, err = pcall(sync)
    if not ok then print("[LX_SplitAim] marker: " .. tostring(err)) end
end)

-- al cambiar resolucion/split, recrear con el tamano nuevo
Events.OnResolutionChange.Add(function()
    if instance and added then instance:removeFromUIManager() end
    instance, added = nil, false
end)
