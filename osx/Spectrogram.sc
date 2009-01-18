
Spectrogram {

	classvar <server;
	var window;
	var <fftbuf, fftDataArray, fftSynth;
	var inbus, <>rate;
	var <bufSize;	// size of FFT
	var image, index, <>intensity, runtask;
	var color, background;
		
	*new { arg parent, bounds, bufSize, color, background;
		^super.new.initSpectrogram(parent, bounds, bufSize, color, background);
	}
	
	initSpectrogram { arg parent, bounds, bufsize, col, bg;
		server = Server.default;
		inbus=0;
		rate = 25; // updates per second
		bufSize = bufsize ? 1024;
		fftbuf = Buffer.alloc(server, bufSize);
		fftDataArray = Int32Array.fill(bufSize, 0);
		index = 0;
		intensity = 5;
		background = bg ? Color.black; // not implemented yet
		color = col ? Color(1, 1, 1); // white by default
		this.sendSynthDef;
		this.createWindow(parent, bounds);
	}

	createWindow {arg parent, bounds;
		window = parent ? SCWindow("Spectrogram",  Rect(200, 450, 600, 300));
		bounds = bounds ? window.view.bounds;
		[\bounds, bounds].postln;
		image = SCImage.color(bounds.width, bufSize/2, background);
		window.drawHook_({
			index = index+1;
			image.drawInRect(bounds, image.bounds, 2, 1.0);
			if(parent.isNil.not, {
				fftDataArray.do({arg val, ind;
					image.setPixel(val,Êindex%image.bounds.width, ind);
				});
			}, {
				// this cuts off the bottom when graph is a view in a scwindow
				image.setPixels(fftDataArray, Rect(index%image.bounds.width, 0, 1, bufSize/2));
			});
		})
		.onClose_({
			this.stop;
			fftbuf.free;
		}).front;
	}

	sendSynthDef {
		SynthDef(\spectroscope, {|inbus=0, buffer=0|
			FFT(buffer, InFeedback.ar(inbus));
		}).send(server);
	}
		
	start {
		{
		runtask = Task({ 
			fftSynth = Synth(\spectroscope, [\inbus, inbus, \buffer, fftbuf]);
			{ fftbuf.getn(0, bufSize, { arg buf;
				var magarray, complexarray;
				magarray = buf.clump(2).flop;
				complexarray = (Complex( 
						Signal.newFrom( magarray[0] ), 
						Signal.newFrom( magarray[1] ) 
					).magnitude.reverse*2).clip(0, 255);
				complexarray.do({ |val, i|
					val = val * intensity;
				fftDataArray[i] =ÊInteger.fromRGBA(
										(val*color.red).asInteger, 
										(val*color.green).asInteger, 
										(val*color.blue).asInteger,
										255);
			});
				{ window.refresh }.defer;
			}); 
			rate.reciprocal.wait;
			}.loop 
		}).start;
		}.defer(0.1); // allow the creation of fftbuf before starting
	}

	stop {
		runtask.stop;
		try{fftSynth.free };
	}
	
	inbus_ {arg inbus;
		fftSynth.set(\inbus, inbus);
	}

	color_ {arg argcolor;
		color = argcolor;
	}	
	
	background_ {arg argbackground;
		image.free;
		background = argbackground;
		image = SCImage.color(window.bounds.width, bufSize/2, background);
	}
	
}

SpectrogramWindow : Spectrogram {

	*new { ^super.new }

	createWindow {arg parent, bounds;
		var startbutt, cper;
		window = SCWindow("Spectrogram",  Rect(200, 450, 584, 328));
		bounds = window.view.bounds.insetAll(30, 10, 10, 40); // resizable
		image = SCImage.color(bounds.width, bufSize/2, background);
		window.drawHook_({
			index = index+1;
			image.drawInRect(window.view.bounds.insetAll(30, 10, 42, 10), image.bounds, 2, 1.0);
			image.setPixels(fftDataArray, Rect(index%image.bounds.width, 0, 1, bufSize/2));
		});
		
		startbutt = SCButton(window, Rect(544, 10, 38, 16))
			.states_([["Power", Color.black, Color.clear], 
					["Power", Color.black, Color.green.alpha_(0.2)]])
			.action_({ arg view;
				if(view.value == 1, { this.start }, { this.stop });
			})
			.font_(GUI.font.new("Helvetica", 10))
			.resize_(3)
			.canFocus_(false);

		SCStaticText(window, Rect(544, 30, 38, 16))
			.font_(GUI.font.new("Helvetica", 10))
			.resize_(3)
			.string_("BusIn");

		SCNumberBox(window, Rect(544, 50, 38, 16))
			.font_(GUI.font.new("Helvetica", 10))
			.resize_(3)
			.action_({ arg view;
				view.value_(view.value.asInteger.clip(0, server.options.numAudioBusChannels));
				this.inbus_(view.value);
			})
			.value_(0);

		SCStaticText(window, Rect(544, 70, 38, 16))
			.font_(GUI.font.new("Helvetica", 10))
			.resize_(3)
			.string_("int");

		SCNumberBox(window, Rect(544, 90, 38, 16))
			.font_(GUI.font.new("Helvetica", 10))
			.resize_(3)
			.action_({ arg view;
				view.value_(view.value.asInteger.clip(1, 40));
				this.intensity_(view.value);
			})
			.value_(5);
		
		// this needs fixing - on resize the text does not follow
		11.do({ arg i;
			SCStaticText(window, Rect(5, 10+(i*(window.bounds.height/11)), 20, 10))
				.string_(((((server.sampleRate/2) / 10000)*(10-i)).round(1)).asString++"k")
				.font_(GUI.font.new("Helvetica", 10))
				.align_(1);
		});
		CmdPeriod.add( cper = { this.stop; });
		
		window.onClose_({
			try{ fftSynth.free };
			try{ fftbuf.free };
			this.stop;
			CmdPeriod.remove(cper);
		}).front;
	}
	
}
