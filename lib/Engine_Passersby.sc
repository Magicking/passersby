// CroneEngine_Passersby
// West coast style mono synth with complex waveform generation, basic FM and a lowpass gate.
// v1.3.0-dev Mark Eats — multi-channel refactor (phase 1: numChannels = 1, behaviour unchanged)

Engine_Passersby : CroneEngine {

	var numChannels = 4;

	var lfosArr;
	var synthVoices;
	var reverb;

	var lfosBuses;
	var fxBus;

	var replyFunc;

	var numLfoDests = 9;
	var noteLists;
	var activeNoteIds;
	var waveShapeModPolls, waveFoldsModPolls, fm1AmountModPolls, fm2AmountModPolls, attackModPolls, peakMulModPolls, decayModPolls, reverbMixModPolls;

	var startPauseRoutine;
	var pauseRoutines;
	var maxDecay = 8;

	*new { arg context, doneCallback;
		^super.new(context, doneCallback);
	}

	alloc {

		Routine({

			fxBus = Bus.audio(server: context.server, numChannels: 1);
			lfosBuses = Array.fill(numChannels, { Bus.control(server: context.server, numChannels: numLfoDests) });
			noteLists = Array.fill(numChannels, { List.new });
			activeNoteIds = Array.newClear(numChannels);
			synthVoices = Array.newClear(numChannels);
			lfosArr = Array.newClear(numChannels);
			pauseRoutines = Array.newClear(numChannels);

			// LFOs
			SynthDef(\lfos, {

				arg out, ch = 0, lfoShape = 0, lfoFreq = 0.5,
				lfoToFreqAmount = 0, lfoToWaveShapeAmount = 0, lfoToWaveFoldsAmount = 0, lfoToFm1Amount = 0, lfoToFm2Amount = 0,
				lfoToAttackAmount = 0, lfoToPeakAmount = 0, lfoToDecayAmount = 0, lfoToReverbMixAmount = 0, drift = 0;
				var i_driftRate = 0.15, outArray, lfo;

				// Osc
				lfo = Select.kr(lfoShape, [
					LFTri.kr(lfoFreq),
					LFSaw.kr(lfoFreq),
					LFPulse.kr(lfoFreq),
					LFDNoise0.kr(lfoFreq * 2)
				]);

				// Drift and scale
				outArray = Array.fill(numLfoDests, 0);
				outArray[0] = (lfo * lfoToFreqAmount * 18).midiratio; // Freq ratio
				outArray[1] = (lfo * lfoToWaveShapeAmount) + LFNoise1.kr(freq: i_driftRate, mul: drift); // Wave Shape
				outArray[2] = ((lfo * lfoToWaveFoldsAmount) + LFNoise1.kr(freq: i_driftRate, mul: drift)) * 2; // Wave Folds
				outArray[3] = ((lfo * lfoToFm1Amount) + LFNoise1.kr(freq: i_driftRate, mul: drift)) * 0.5; // FM1 Amount
				outArray[4] = ((lfo * lfoToFm2Amount) + LFNoise1.kr(freq: i_driftRate, mul: drift)) * 0.5; // FM2 Amount
				outArray[5] = ((lfo * lfoToAttackAmount) + LFNoise1.kr(freq: i_driftRate, mul: drift)) * 2.2; // Attack
				outArray[6] = (((lfo * lfoToPeakAmount) + LFNoise1.kr(freq: i_driftRate, mul: drift)) * 24).midiratio; // Peak multiplier
				outArray[7] = ((lfo * lfoToDecayAmount) + LFNoise1.kr(freq: i_driftRate, mul: drift)) * 2.2; // Decay
				outArray[8] = (lfo * lfoToReverbMixAmount) + LFNoise1.kr(freq: i_driftRate, mul: drift); // Reverb Mix

				// Prepend channel index so replyFunc can route to per-channel polls (phase 2)
				SendReply.kr(trig: Impulse.kr(15), cmdName: '/replyLfos', values: [ch] ++ outArray);
				Out.kr(out, outArray);

			}).add;

			// Synth voice
			SynthDef(\synthVoice, {

				arg out, lfosIn, t_gate, gate, killGate, freq = 220, pitchBendRatio = 1, glide = 0, fm1Ratio = 0.66, fm2Ratio = 3.3, fm1Amount = 0.0, fm2Amount = 0.0,
				vel = 0.7, pressure = 0, timbre = 0, waveShape = 0, waveFolds = 0, envType = 0, attack = 0.04, peak = 10000, decay = 1, amp = 1;

				var i_nyquist = SampleRate.ir * 0.5, signal, controlLag = 0.005, i_numHarmonics = 44,
				modFreq, mod1, mod2, mod1Index, mod2Index, mod1Freq, mod2Freq, sinOsc, triOsc, additiveOsc, additivePhase,
				filterEnvVel, filterEnvLow, lpgEnvelope, lpgSignal, asrEnvelope, asrFilterFreq, asrSignal, killEnvelope;

				// LFO ins
				freq = (freq * In.kr(lfosIn, numLfoDests)[0]).clip(0, i_nyquist);
				waveShape = (waveShape + In.kr(lfosIn, numLfoDests)[1]).clip(0, 1);
				waveFolds = (waveFolds + In.kr(lfosIn, numLfoDests)[2]).clip(0, 3);
				fm1Amount = (fm1Amount + In.kr(lfosIn, numLfoDests)[3]).clip(0, 1);
				fm2Amount = (fm2Amount + In.kr(lfosIn, numLfoDests)[4]).clip(0, 1);
				attack = (attack + In.kr(lfosIn, numLfoDests)[5]).clip(0.003, 8);
				peak = (peak * In.kr(lfosIn, numLfoDests)[6]).clip(100, 10000);
				decay = (decay + In.kr(lfosIn, numLfoDests)[7]).clip(0.01, maxDecay);

				// Lag inputs
				freq = Lag.kr(freq * pitchBendRatio, 0.007 + glide);
				fm1Ratio = Lag.kr(fm1Ratio, controlLag);
				fm2Ratio = Lag.kr(fm2Ratio, controlLag);
				fm1Amount = Lag.kr(fm1Amount.squared, controlLag);
				fm2Amount = Lag.kr(fm2Amount.squared, controlLag);

				vel = Lag.kr(vel, controlLag);
				waveShape = Lag.kr(waveShape, controlLag);
				waveFolds = Lag.kr(waveFolds, controlLag);
				attack = Lag.kr(attack, controlLag);
				peak = Lag.kr(peak, controlLag);
				decay = Lag.kr(decay, controlLag);

				// Modulators
				mod1Index = fm1Amount * 22;
				mod1Freq = freq * fm1Ratio * LFNoise2.kr(freq: 0.1, mul: 0.001, add: 1);
				mod1 = SinOsc.ar(freq: mod1Freq, phase: 0, mul: mod1Index * mod1Freq, add: 0);
				mod2Index = fm2Amount * 12;
				mod2Freq = freq * fm2Ratio * LFNoise2.kr(freq: 0.1, mul: 0.005, add: 1);
				mod2 = SinOsc.ar(freq: mod2Freq, phase: 0, mul: mod2Index * mod2Freq, add: 0);
				modFreq = freq + mod1 + mod2;

				// Sine and triangle
				sinOsc = SinOsc.ar(freq: modFreq, phase: 0, mul: 0.5);
				triOsc = VarSaw.ar(freq: modFreq, iphase: 0, width: 0.5, mul: 0.5);

				// Additive square and saw
				additivePhase = LFSaw.ar(freq: modFreq, iphase: 1, mul: pi, add: pi);
				additiveOsc = Mix.fill(i_numHarmonics, {
					arg index;
					var harmonic, harmonicFreq, harmonicCutoff, attenuation;

					harmonic = index + 1;
					harmonicFreq = freq * harmonic;
					harmonicCutoff = i_nyquist - harmonicFreq;

					// Attenuate harmonics that will go over nyquist once FM is applied
					attenuation = Select.kr(index, [1, // Save the fundamental
						(harmonicCutoff - (harmonicFreq * 0.25) - harmonicFreq).expexp(0.000001, harmonicFreq * 0.5, 0.000001, 1)]);

					(sin(additivePhase * harmonic % 2pi) / harmonic) * attenuation * (harmonic % 2 + waveShape.linlin(0.666666, 1, 0, 1)).min(1);
				});

				// Mix carriers
				signal = LinSelectX.ar(waveShape * 3, [sinOsc, triOsc, additiveOsc]);

				// Fold
				signal = Fold.ar(in: signal * (1 + (timbre * 0.5) + (waveFolds * 2)), lo: -0.5, hi: 0.5);

				// Hack away some aliasing
				signal = LPF.ar(in: signal, freq: 12000);

				// Noise
				signal = signal + PinkNoise.ar(mul: 0.003);

				// LPG
				filterEnvVel = vel.linlin(0, 1, 0.5, 1);
				filterEnvLow = (peak * filterEnvVel).min(300);

				lpgEnvelope = EnvGen.ar(envelope: Env.new(levels: [0, 1, 0], times: [0.003, decay], curve: [4, -20]), gate: t_gate);
				lpgSignal = RLPF.ar(in: signal, freq: lpgEnvelope.linlin(0, 1, filterEnvLow, peak * filterEnvVel), rq: 0.9);
				lpgSignal = lpgSignal * EnvGen.ar(envelope: Env.new(levels: [0, 1, 0], times: [0.002, decay], curve: [4, -10]), gate: t_gate);

				// ASR with 4-pole filter
				asrEnvelope = EnvGen.ar(envelope: Env.new(levels: [0, 1, 0], times: [attack, decay], curve: -4, releaseNode: 1), gate: gate);
				asrFilterFreq = asrEnvelope.linlin(0, 1, filterEnvLow, peak * filterEnvVel);
				asrSignal = RLPF.ar(in: signal, freq: asrFilterFreq, rq: 0.95);
				asrSignal = RLPF.ar(in: asrSignal, freq: asrFilterFreq, rq: 0.95);
				asrSignal = asrSignal * EnvGen.ar(envelope: Env.asr(attackTime: attack, sustainLevel: 1, releaseTime: decay, curve: -4), gate: gate);

				signal = Select.ar(envType, [lpgSignal, asrSignal]);

				killEnvelope = EnvGen.kr(envelope: Env.asr( 0, 1, 0.01), gate: killGate);
				signal = signal * vel.linlin(0, 1, 0.2, 1) * killEnvelope;

				// Saturation amp
				signal = tanh(signal * pressure.linlin(0, 1, 1.5, 3) * amp).softclip;

				Out.ar(out, signal);

			}).add;


			// Very approx spring reverb — shared across channels (phase 1)
			SynthDef(\reverb, {

				arg in, out, lfosIn, mix = 0;
				var dry, preProcess, springReso, wet, predelay = 0.015;

				mix = (mix + In.kr(lfosIn, numLfoDests)[8]).clip(0, 1);
				mix = Lag.kr(mix, 0.01);

				dry = In.ar(in, 1);

				preProcess = tanh(BHiShelf.ar(in: dry, freq: 1000, rs: 1, db: -6, mul: 1.5, add: 0)); // Darken and saturate
				preProcess = DelayN.ar(in: preProcess, maxdelaytime: predelay, delaytime: predelay);
				springReso = Klank.ar(specificationsArrayRef: `[[508, 270, 1153], [0.15, 0.25, 0.1], [1, 1.2, 1.4]], input: preProcess);
				springReso = Limiter.ar(springReso).dup;
				preProcess = preProcess * 0.55; // FreeVerb doesn't like a loud signal
				wet = tanh(FreeVerb2.ar(in: preProcess, in2: preProcess, mix: 1, room: 0.7, damp: 0.35, mul: 1.8));
				wet = (wet * 0.935) + (springReso * 0.065);

				Out.ar(out, (dry.dup * (1 - mix)) + (wet * mix));

			}).add;

			context.server.sync; // Wait for all the SynthDefs to be added on the server

			numChannels.do { |i|
				lfosArr[i] = Synth(defName: \lfos, args: [\out, lfosBuses[i], \ch, i], target: context.xg);

				synthVoices[i] = Synth.newPaused(defName: \synthVoice, args: [
					\out, fxBus,
					\lfosIn, lfosBuses[i]
				], target: context.xg, addAction: \addToTail);
			};

			reverb = Synth(defName: \reverb, args: [
				\in, fxBus,
				\out, context.out_b,
				\lfosIn, lfosBuses[0]
			], target: context.xg, addAction: \addToTail);

		}).play;


		// Per-channel polls (Lua subscribes to e.g. waveShapeMod_0..waveShapeMod_(numChannels-1))
		waveShapeModPolls = Array.fill(numChannels, { |i| this.addPoll(name: "waveShapeMod_" ++ i, periodic: false) });
		waveFoldsModPolls = Array.fill(numChannels, { |i| this.addPoll(name: "waveFoldsMod_" ++ i, periodic: false) });
		fm1AmountModPolls = Array.fill(numChannels, { |i| this.addPoll(name: "fm1AmountMod_" ++ i, periodic: false) });
		fm2AmountModPolls = Array.fill(numChannels, { |i| this.addPoll(name: "fm2AmountMod_" ++ i, periodic: false) });
		attackModPolls    = Array.fill(numChannels, { |i| this.addPoll(name: "attackMod_" ++ i, periodic: false) });
		peakMulModPolls   = Array.fill(numChannels, { |i| this.addPoll(name: "peakMulMod_" ++ i, periodic: false) });
		decayModPolls     = Array.fill(numChannels, { |i| this.addPoll(name: "decayMod_" ++ i, periodic: false) });
		reverbMixModPolls = Array.fill(numChannels, { |i| this.addPoll(name: "reverbMixMod_" ++ i, periodic: false) });

		// Receive messages from server
		// Reply payload: [ch, freqMod, waveShapeMod, waveFoldsMod, fm1Mod, fm2Mod, attackMod, peakMulMod, decayMod, reverbMixMod]
		// OSC indices: msg[3]=ch, msg[4]=freqMod, msg[5]=waveShapeMod, ...
		replyFunc = OSCFunc({
			arg msg;
			var ch = msg[3].asInteger;
			if((ch >= 0) && (ch < numChannels), {
				waveShapeModPolls[ch].update(msg[5]);
				waveFoldsModPolls[ch].update(msg[6]);
				fm1AmountModPolls[ch].update(msg[7]);
				fm2AmountModPolls[ch].update(msg[8]);
				attackModPolls[ch].update(msg[9]);
				peakMulModPolls[ch].update(msg[10]);
				decayModPolls[ch].update(msg[11]);
				reverbMixModPolls[ch].update(msg[12]);
			});
		}, path: '/replyLfos', srcID: context.server.addr);

		this.addCommands;
	}


	addCommands {

		startPauseRoutine = { |ch|
			pauseRoutines[ch] = Routine {
				(maxDecay + 0.01).wait;
				if(synthVoices[ch].notNil, {
					synthVoices[ch].run(false);
				});
			}.play;
		};

		// noteOn(ch, id, freq, vel)
		this.addCommand(\noteOn, "iiff", { arg msg;

			var ch = msg[1] - 1, id = msg[2], freq = msg[3], vel = msg[4], note = Dictionary.new(2);
			var voice = synthVoices[ch];
			var noteList = noteLists[ch];

			noteList.remove(noteList.detect{arg item; item[\id] == id});

			if(voice.notNil, {
				if(pauseRoutines[ch].notNil, { pauseRoutines[ch].stop; });
				voice.run(true);
				voice.set(\freq, freq, \vel, vel, \t_gate, 1, \gate, 1, \killGate, 1);

				note[\id] = id;
				note[\freq] = freq;
				noteList.add(note);
				activeNoteIds[ch] = id;
			});
		});

		// noteOff(ch, id)
		this.addCommand(\noteOff, "ii", { arg msg;

			var ch = msg[1] - 1, id = msg[2];
			var voice = synthVoices[ch];
			var noteList = noteLists[ch];

			noteList.remove(noteList.detect{arg item; item[\id] == id});

			if(id == activeNoteIds[ch], {
				if(noteList.size > 0, {
					voice.set(\freq, noteList.last.[\freq]);
					activeNoteIds[ch] = noteList.last.[\id];
				}, {
					voice.set(\gate, 0);
					activeNoteIds[ch] = Nil;
					if(pauseRoutines[ch].notNil, { pauseRoutines[ch].stop; });
					startPauseRoutine.value(ch);
				});
			});
		});

		// noteOffAll(ch)
		this.addCommand(\noteOffAll, "i", { arg msg;
			var ch = msg[1] - 1;
			synthVoices[ch].set(\gate, 0);
			activeNoteIds[ch] = Nil;
			noteLists[ch].clear;
			if(pauseRoutines[ch].notNil, { pauseRoutines[ch].stop; });
			startPauseRoutine.value(ch);
		});

		// noteKill(ch, id)
		this.addCommand(\noteKill, "ii", { arg msg;

			var ch = msg[1] - 1, id = msg[2];
			var voice = synthVoices[ch];
			var noteList = noteLists[ch];

			noteList.remove(noteList.detect{arg item; item[\id] == id});

			if(id == activeNoteIds[ch], {
				if(noteList.size > 0, {
					voice.set(\freq, noteList.last.[\freq]);
					activeNoteIds[ch] = noteList.last.[\id];
				}, {
					voice.set(\gate, 0);
					voice.set(\killGate, 0);
					activeNoteIds[ch] = Nil;
					if(pauseRoutines[ch].notNil, { pauseRoutines[ch].stop; });
					startPauseRoutine.value(ch);
				});
			});
		});

		// noteKillAll(ch)
		this.addCommand(\noteKillAll, "i", { arg msg;
			var ch = msg[1] - 1;
			synthVoices[ch].set(\gate, 0);
			synthVoices[ch].set(\killGate, 0);
			activeNoteIds[ch] = Nil;
			noteLists[ch].clear;
			if(pauseRoutines[ch].notNil, { pauseRoutines[ch].stop; });
			startPauseRoutine.value(ch);
		});

		// pitchBend(ch, id, ratio)
		this.addCommand(\pitchBend, "iif", { arg msg;
			synthVoices[msg[1] - 1].set(\pitchBendRatio, msg[3]);
		});

		// pitchBendAll(ch, ratio)
		this.addCommand(\pitchBendAll, "if", { arg msg;
			synthVoices[msg[1] - 1].set(\pitchBendRatio, msg[2]);
		});

		// pressure(ch, id, pressure)
		this.addCommand(\pressure, "iif", { arg msg;
			synthVoices[msg[1] - 1].set(\pressure, msg[3]);
		});

		// pressureAll(ch, pressure)
		this.addCommand(\pressureAll, "if", { arg msg;
			synthVoices[msg[1] - 1].set(\pressure, msg[2]);
		});

		// timbre(ch, id, timbre)
		this.addCommand(\timbre, "iif", { arg msg;
			synthVoices[msg[1] - 1].set(\timbre, msg[3]);
		});

		// timbreAll(ch, timbre)
		this.addCommand(\timbreAll, "if", { arg msg;
			synthVoices[msg[1] - 1].set(\timbre, msg[2]);
		});

		this.addCommand(\glide, "if", { arg msg;
			synthVoices[msg[1] - 1].set(\glide, msg[2]);
		});

		this.addCommand("waveShape", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\waveShape, msg[2]);
		});

		this.addCommand("waveFolds", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\waveFolds, msg[2]);
		});

		this.addCommand("fm1Ratio", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\fm1Ratio, msg[2]);
		});

		this.addCommand("fm2Ratio", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\fm2Ratio, msg[2]);
		});

		this.addCommand("fm1Amount", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\fm1Amount, msg[2]);
		});

		this.addCommand("fm2Amount", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\fm2Amount, msg[2]);
		});

		this.addCommand("envType", "ii", { arg msg;
			synthVoices[msg[1] - 1].set(\envType, msg[2]);
		});

		this.addCommand("attack", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\attack, msg[2]);
		});

		this.addCommand("peak", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\peak, msg[2]);
		});

		this.addCommand("decay", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\decay, msg[2]);
		});

		this.addCommand("amp", "if", { arg msg;
			synthVoices[msg[1] - 1].set(\amp, msg[2]);
		});

		// reverbMix(value) — shared reverb in phase 1, no ch arg
		this.addCommand("reverbMix", "f", { arg msg;
			reverb.set(\mix, msg[1]);
		});

		this.addCommand("lfoShape", "ii", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoShape, msg[2]);
		});

		this.addCommand("lfoFreq", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoFreq, msg[2]);
		});

		this.addCommand("lfoToFreqAmount", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoToFreqAmount, msg[2]);
		});

		this.addCommand("lfoToWaveShapeAmount", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoToWaveShapeAmount, msg[2]);
		});

		this.addCommand("lfoToWaveFoldsAmount", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoToWaveFoldsAmount, msg[2]);
		});

		this.addCommand("lfoToFm1Amount", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoToFm1Amount, msg[2]);
		});

		this.addCommand("lfoToFm2Amount", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoToFm2Amount, msg[2]);
		});

		this.addCommand("lfoToAttackAmount", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoToAttackAmount, msg[2]);
		});

		this.addCommand("lfoToPeakAmount", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoToPeakAmount, msg[2]);
		});

		this.addCommand("lfoToDecayAmount", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoToDecayAmount, msg[2]);
		});

		this.addCommand("lfoToReverbMixAmount", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\lfoToReverbMixAmount, msg[2]);
		});

		this.addCommand("drift", "if", { arg msg;
			lfosArr[msg[1] - 1].set(\drift, msg[2]);
		});

	}


	free {
		lfosArr.do({ |s| if(s.notNil, { s.free }); });
		synthVoices.do({ |s| if(s.notNil, { s.free }); });
		reverb.free;
		replyFunc.free;
	}
}
