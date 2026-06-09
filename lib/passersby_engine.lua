--- Passersby Engine lib
-- Engine params and functions.
--
-- @module PassersbyEngine
-- @release v1.3.0-dev
-- @author Mark Eats

local ControlSpec = require "controlspec"
local Formatters = require "formatters"

local Passersby = {}

local specs = {}

specs.GLIDE = ControlSpec.new(0, 5, "lin", 0, 0, "s")
specs.WAVE_SHAPE = ControlSpec.UNIPOLAR
specs.WAVE_FOLDS = ControlSpec.new(0.0, 3.0, "lin", 0, 0)
specs.FM_LOW_RATIO = ControlSpec.new(0.1, 1, "lin", 0, 0.66)
specs.FM_HIGH_RATIO = ControlSpec.new(1, 10, "lin", 0, 3.3)
specs.FM_LOW_AMOUNT = ControlSpec.UNIPOLAR
specs.FM_HIGH_AMOUNT = ControlSpec.UNIPOLAR
specs.ATTACK = ControlSpec.new(0.003, 8, "exp", 0, 0.04, "s")
specs.PEAK = ControlSpec.new(100, 10000, "exp", 0, 10000, "Hz")
specs.DECAY = ControlSpec.new(0.01, 8, "exp", 0, 1, "s")
specs.AMP = ControlSpec.new(0, 11, "lin", 0, 1, "")
specs.REVERB_MIX = ControlSpec.UNIPOLAR
specs.LFO_FREQ = ControlSpec.new(0.001, 10.0, "exp", 0, 0.5, "Hz")
specs.LFO_AMOUNT = ControlSpec.UNIPOLAR
specs.DRIFT = ControlSpec.UNIPOLAR

Passersby.specs = specs


local function format_wave_shape(param)
  local value = param:get()
  local wave_names = {}

  if value < 0.28 then table.insert(wave_names, "Sine") end
  if value > 0.05 and value < 0.64 then table.insert(wave_names, "Tri") end
  if value > 0.38 and value < 0.95 then table.insert(wave_names, "Sqr") end
  if value > 0.71 then table.insert(wave_names, "Saw") end

  local return_string = ""
  for i = 1, #wave_names do
    if i > 1 then return_string = return_string .. "/" end
    return_string = return_string .. wave_names[i]
  end
  return return_string .. " " .. util.round(value, 0.01)
end

-- Closure factory so each channel's attack formatter reads its own env_type
local function make_format_attack(prefix)
  return function(param)
    if params:get(prefix .. "env_type") == 1 then
      return "N/A"
    else
      return Formatters.format_secs(param)
    end
  end
end

-- Global params (shared across channels)
function Passersby.add_global_params()
  params:add_separator("Reverb")
  params:add{type = "control", id = "reverb_mix", name = "Reverb Mix", controlspec = specs.REVERB_MIX, action = engine.reverbMix}
end

-- Per-voice params, namespaced as "ch<N>_<id>".
function Passersby.add_voice_params(ch)
  local prefix = "ch" .. ch .. "_"
  local label = "Channel " .. ch

  params:add_separator(label .. " — Input")
  params:add{type = "number", id = prefix .. "midi_channel", name = label .. " MIDI Channel", min = 0, max = 16, default = ch,
    formatter = function(p) local v = p:get(); if v == 0 then return "Off" else return tostring(v) end end}

  params:add_separator(label .. " — Oscillators")
  params:add{type = "control", id = prefix .. "glide", name = label .. " Glide", controlspec = specs.GLIDE, action = function(v) engine.glide(ch, v) end}
  params:add{type = "control", id = prefix .. "wave_shape", name = label .. " Wave Shape", controlspec = specs.WAVE_SHAPE, formatter = format_wave_shape, action = function(v) engine.waveShape(ch, v) end}
  params:add{type = "control", id = prefix .. "wave_folds", name = label .. " Wave Folds", controlspec = specs.WAVE_FOLDS, action = function(v) engine.waveFolds(ch, v) end}
  params:add{type = "control", id = prefix .. "fm_low_ratio", name = label .. " FM Low Ratio", controlspec = specs.FM_LOW_RATIO, action = function(v) engine.fm1Ratio(ch, v) end}
  params:add{type = "control", id = prefix .. "fm_high_ratio", name = label .. " FM High Ratio", controlspec = specs.FM_HIGH_RATIO, action = function(v) engine.fm2Ratio(ch, v) end}
  params:add{type = "control", id = prefix .. "fm_low_amount", name = label .. " FM Low Amount", controlspec = specs.FM_LOW_AMOUNT, action = function(v) engine.fm1Amount(ch, v) end}
  params:add{type = "control", id = prefix .. "fm_high_amount", name = label .. " FM High Amount", controlspec = specs.FM_HIGH_AMOUNT, action = function(v) engine.fm2Amount(ch, v) end}

  params:add_separator(label .. " — LPG")
  params:add{type = "option", id = prefix .. "env_type", name = label .. " Envelope Type", options = {"LPG", "Sustain"}, action = function(value)
    engine.envType(ch, value - 1)
  end}
  params:add{type = "control", id = prefix .. "attack", name = label .. " Attack", controlspec = specs.ATTACK, formatter = make_format_attack(prefix), action = function(v) engine.attack(ch, v) end}
  params:add{type = "control", id = prefix .. "peak", name = label .. " Peak", controlspec = specs.PEAK, formatter = Formatters.format_freq, action = function(v) engine.peak(ch, v) end}
  params:add{type = "control", id = prefix .. "decay", name = label .. " Decay", controlspec = specs.DECAY, formatter = Formatters.format_secs, action = function(v) engine.decay(ch, v) end}
  params:add{type = "control", id = prefix .. "amp", name = label .. " Amp", controlspec = specs.AMP, action = function(v) engine.amp(ch, v) end}

  params:add_separator(label .. " — LFO")
  params:add{type = "option", id = prefix .. "lfo_shape", name = label .. " LFO Shape", options = {"Triangle", "Ramp", "Square", "Random"}, action = function(value)
    engine.lfoShape(ch, value - 1)
  end}
  params:add{type = "control", id = prefix .. "lfo_freq", name = label .. " LFO Frequency", controlspec = specs.LFO_FREQ, formatter = Formatters.format_freq, action = function(v) engine.lfoFreq(ch, v) end}
  params:add{type = "control", id = prefix .. "lfo_to_freq_amount", name = label .. " LFO > Frequency", controlspec = specs.LFO_AMOUNT, action = function(v) engine.lfoToFreqAmount(ch, v) end}
  params:add{type = "control", id = prefix .. "lfo_to_wave_shape_amount", name = label .. " LFO > Wave Shape", controlspec = specs.LFO_AMOUNT, action = function(v) engine.lfoToWaveShapeAmount(ch, v) end}
  params:add{type = "control", id = prefix .. "lfo_to_wave_folds_amount", name = label .. " LFO > Wave Folds", controlspec = specs.LFO_AMOUNT, action = function(v) engine.lfoToWaveFoldsAmount(ch, v) end}
  params:add{type = "control", id = prefix .. "lfo_to_fm_low_amount", name = label .. " LFO > FM Low", controlspec = specs.LFO_AMOUNT, action = function(v) engine.lfoToFm1Amount(ch, v) end}
  params:add{type = "control", id = prefix .. "lfo_to_fm_high_amount", name = label .. " LFO > FM High", controlspec = specs.LFO_AMOUNT, action = function(v) engine.lfoToFm2Amount(ch, v) end}
  params:add{type = "control", id = prefix .. "lfo_to_attack_amount", name = label .. " LFO > Attack", controlspec = specs.LFO_AMOUNT, action = function(v) engine.lfoToAttackAmount(ch, v) end}
  params:add{type = "control", id = prefix .. "lfo_to_peak_amount", name = label .. " LFO > Peak", controlspec = specs.LFO_AMOUNT, action = function(v) engine.lfoToPeakAmount(ch, v) end}
  params:add{type = "control", id = prefix .. "lfo_to_decay_amount", name = label .. " LFO > Decay", controlspec = specs.LFO_AMOUNT, action = function(v) engine.lfoToDecayAmount(ch, v) end}
  params:add{type = "control", id = prefix .. "lfo_to_reverb_mix_amount", name = label .. " LFO > Reverb Mix", controlspec = specs.LFO_AMOUNT, action = function(v) engine.lfoToReverbMixAmount(ch, v) end}

  params:add_separator(label .. " — Fate")
  params:add{type = "control", id = prefix .. "drift", name = label .. " Drift", controlspec = specs.DRIFT, action = function(v) engine.drift(ch, v) end}
  params:add{type = "trigger", id = prefix .. "randomize", name = label .. " Randomize", action = function() Passersby.randomize_params(ch) end}
end

-- Randomise a single channel
function Passersby.randomize_params(ch)
  local p = "ch" .. ch .. "_"
  if math.random() > 0.8 then params:set(p .. "glide", util.linlin(0, 1, Passersby.specs.GLIDE.minval, Passersby.specs.GLIDE.maxval, math.pow(math.random(), 2))) else params:set(p .. "glide", 0) end
  params:set(p .. "wave_shape", math.random())
  params:set(p .. "wave_folds", util.linlin(0, 1, Passersby.specs.WAVE_FOLDS.minval, Passersby.specs.WAVE_FOLDS.maxval, math.pow(math.random(), 2)))
  if math.random() > 0.55 then params:set(p .. "fm_low_amount", math.pow(math.random(), 4)) else params:set(p .. "fm_low_amount", 0) end
  if math.random() > 0.55 then params:set(p .. "fm_high_amount", math.pow(math.random(), 4)) else params:set(p .. "fm_high_amount", 0) end
  params:set(p .. "env_type", math.random(1, 2))
  params:set(p .. "attack", util.linlin(0, 1, Passersby.specs.ATTACK.minval, Passersby.specs.ATTACK.maxval, math.pow(math.random(), 4)))
  params:set(p .. "peak", util.linlin(0, 1, Passersby.specs.PEAK.minval, Passersby.specs.PEAK.maxval, math.random()))
  params:set(p .. "decay", util.linlin(0, 1, Passersby.specs.DECAY.minval, Passersby.specs.DECAY.maxval, math.pow(math.random(), 2)))
  if math.random() > 0.5 then params:set(p .. "amp", util.linlin(0, 1, 0.7, Passersby.specs.AMP.maxval, math.pow(math.random(), 4))) else params:set(p .. "amp", 1) end
  params:set(p .. "lfo_shape", math.random(1, 4))
  params:set(p .. "lfo_freq", util.linlin(0, 1, Passersby.specs.LFO_FREQ.minval, Passersby.specs.LFO_FREQ.maxval, math.random()))
  local LFO_RAND_THRESHOLD = 0.65
  if math.random() > 0.75              then params:set(p .. "lfo_to_freq_amount", math.pow(math.random(), 2)) else params:set(p .. "lfo_to_freq_amount", 0) end
  if math.random() > LFO_RAND_THRESHOLD then params:set(p .. "lfo_to_wave_shape_amount", math.random()) else params:set(p .. "lfo_to_wave_shape_amount", 0) end
  if math.random() > LFO_RAND_THRESHOLD then params:set(p .. "lfo_to_wave_folds_amount", math.random()) else params:set(p .. "lfo_to_wave_folds_amount", 0) end
  if math.random() > 0.75              then params:set(p .. "lfo_to_fm_low_amount", math.random()) else params:set(p .. "lfo_to_fm_low_amount", 0) end
  if math.random() > 0.75              then params:set(p .. "lfo_to_fm_high_amount", math.random()) else params:set(p .. "lfo_to_fm_high_amount", 0) end
  if math.random() > LFO_RAND_THRESHOLD then params:set(p .. "lfo_to_attack_amount", math.random()) else params:set(p .. "lfo_to_attack_amount", 0) end
  if math.random() > LFO_RAND_THRESHOLD then params:set(p .. "lfo_to_peak_amount", math.random()) else params:set(p .. "lfo_to_peak_amount", 0) end
  if math.random() > LFO_RAND_THRESHOLD then params:set(p .. "lfo_to_decay_amount", math.random()) else params:set(p .. "lfo_to_decay_amount", 0) end
  if math.random() > LFO_RAND_THRESHOLD then params:set(p .. "lfo_to_reverb_mix_amount", math.random()) else params:set(p .. "lfo_to_reverb_mix_amount", 0) end
end

return Passersby
