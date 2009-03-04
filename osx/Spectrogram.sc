
Spectrogram {
	classvar <server;
	var window, bounds;
	var <fftbuf, fftDataArray, fftSynth;
	var inbus, <>rate;
	var <bufSize, binfreqs;	// size of FFT
	var <frombin, <tobin;
	var image, index, <>intensity, runtask;
	var color, background, colints; // colints is an array of integers each representing a color
	
	*new { arg parent, bounds, bufSize, color, background, lowfreq=0, highfreq=inf;
		^super.new.initSpectrogram(parent, bounds, bufSize, color, background, lowfreq, highfreq);
	}
	
	initSpectrogram { arg parent, boundsarg, bufSizearg, col, bg, lowfreqarg, highfreqarg;
		server = Server.default;
		inbus = 0;
		rate = 25; // updates per second
		bufSize = bufSizearg ? 1024; // fft window
		fftbuf = Buffer.alloc(server, bufSize);
		binfreqs = bufSize.collect({|i| ((server.sampleRate/2)/bufSize)*(i+1)});
		index = 0;
		intensity = 5;
		background = bg ? Color.black;
		color = col ? Color(1, 1, 1); // white by default
		tobin = min(binfreqs.indexOf((highfreqarg/2).nearestInList(binfreqs)), bufSize / 2 - 1);
		frombin = max(binfreqs.indexOf((lowfreqarg/2).nearestInList(binfreqs)), 0);
		fftDataArray = Int32Array.fill((tobin - frombin + 1), 0);
		this.sendSynthDef;
		this.createWindow(parent, boundsarg);
	}

	createWindow {arg parent, boundsarg;
		window = parent ? SCWindow("Spectrogram",  Rect(200, 450, 600, 300));
		bounds = boundsarg ? window.view.bounds;
		this.setWindowImage;
		window.onClose_({
			image.free;
			this.stopruntask;
			fftbuf.free;
		}).front;
	}

	sendSynthDef {
		SynthDef(\spectroscope, {|inbus=0, buffer=0|
			FFT(buffer, InFeedback.ar(inbus));
		}).send(server);
	}
		
	startruntask {
		this.recalcGradient;
		{
			runtask = Task({ 
				fftSynth = Synth(\spectroscope, [\inbus, inbus, \buffer, fftbuf]);
				{
					fftbuf.getn(0, bufSize, 
					{ arg buf;
						var magarray, complexarray;
						magarray = buf.clump(2)[(frombin .. tobin)].flop;
						complexarray = (Complex( 
								Signal.newFrom( magarray[0] ), 
								Signal.newFrom( magarray[1] ) 
							).magnitude.reverse*2).clip(0, 255); // times 2 in order to strenghten color
						complexarray.do({|val, i|
							val = val * intensity;
							fftDataArray[i] = colints.clipAt((val/16).round);
						});
						{ window.refresh }.defer;
					}); 
					rate.reciprocal.wait; // framerate
				}.loop; 
			}).start;
		}.defer(0.1); // allow the creation of an fftbuf before starting
	}

	stopruntask {
		runtask.stop;
		try{fftSynth.free };
	}
	
	inbus_ {arg inbusarg;
		inbus = inbusarg;
		fftSynth.set(\inbus, inbus);
	}

	color_ {arg colorarg;
		color = colorarg;
		this.recalcGradient;
	}	
		
	background_ {arg backgroundarg;
		background = backgroundarg;
		image.free;
		image = SCImage.color(window.bounds.width, bufSize/2, background);
		this.recalcGradient;
	}
	
	setBufSize_ {arg buffersize, restart=true;
		if(buffersize.isPowerOfTwo, {
			this.stopruntask;
			bufSize = buffersize;
			try {fftbuf.free};
			fftbuf = Buffer.alloc(server, bufSize, 1, { if(restart, {this.startruntask}) }) ;
			binfreqs = bufSize.collect({|i| ((server.sampleRate/2)/bufSize)*(i+1) });
			tobin = bufSize / 2 - 1;
			frombin = 0;
			fftDataArray = Int32Array.fill((tobin - frombin + 1), 0);
			this.setWindowImage;
		}, {
			"Buffersize has to be power of two (256, 1024, 2048, etc.)".warn;
		});
	}

	recalcGradient {
		var colors;
		colors = (0..16).collect{|val| blend(background, color, val/16)};
		colints = colors.collect{|col|
			Integer.fromRGBA(
				(col.red * 255 ).asInteger, 
				(col.green * 255).asInteger, 
				(col.blue * 255 ).asInteger, 
				(col.alpha * 255 ).asInteger);
		};
	}
	
	setWindowImage {
		if(image.isNil.not, {image.free});
		image = SCImage.color(bounds.width, (tobin - frombin + 1), background);
		index = 0;
		window.drawHook_({
			index = index + 1;
			image.drawInRect(bounds, image.bounds, 2, 1.0);
			image.setPixels(fftDataArray, Rect(index%bounds.width, 0, 1, (tobin - frombin + 1))) ;
			// fftDataArray.do({arg val, ind; image.setPixel(val,Êindex%bounds.width, ind) });
		});
	}
	
	start { this.startruntask }
	
	stop { this.stopruntask }
	
}

SpectrogramWindow : Spectrogram { 
	classvar <scopeOpen;
	var startbutt, userview, mouseX, mouseY, mYIndex, mXIndex, freq;
	var crosshaircolor;
	
	*new { 
		^super.new;
	}

	createWindow {
		var cper, font, crosshairCalcFunc;
		var highfreq, lowfreq, rangeslider, freqtextarray;		scopeOpen = true;
		window = Window("Spectrogram",  Rect(200, 450, 584, 328));
		bounds = window.view.bounds.insetAll(30, 10, 40, 10); // resizable
		font = Font("Helvetica", 10);
		mouseX=30.5; mouseY=30.5;
		crosshaircolor = Color.white;
		this.setWindowImage;
		
		// userview made for detecting frequencies using a crosshair 
		Pen.font = Font( "Helvetica", 10 );
		userview = SCUserView(window, bounds)
			.relativeOrigin_(false)
			.focusColor_(Color.white.alpha_(0))
			.resize_(5)
			.backgroundImage_(image, 10)
			.drawFunc_({			
					index = index + 1;
					image.drawInRect(window.view.bounds.insetAll(30, 10, 42, 10), image.bounds, 2, 1.0);
					image.setPixels(fftDataArray, Rect(index%bounds.width, 0, 1, (tobin - frombin + 1)));
			})
			.mouseDownAction_({|view, mx, my|
				crosshairCalcFunc.value(view, mx, my);
				view.drawFunc_({
					index = index + 1;
					image.drawInRect(window.view.bounds.insetAll(30, 10, 42, 10), image.bounds, 2, 1.0);
					image.setPixels(fftDataArray, Rect(index%bounds.width, 0, 1, (tobin - frombin + 1)));
					Pen.color = crosshaircolor;
					Pen.line( 29.5@mouseY, view.bounds.left+view.bounds.width@mouseY);
					Pen.line(mouseX @ 10, mouseX @ (view.bounds.height+10));
					Pen.stringAtPoint( "freq: "+freq.asString, mouseX + 20 @ mouseY - 15);
					Pen.stroke;
				});
			})
			.mouseMoveAction_({|view, mx, my| 
				crosshairCalcFunc.value(view, mx, my);
			})
			.mouseUpAction_({|view, mx, my|Ê 
				view.drawFunc_({
					index = index + 1;
					image.drawInRect(window.view.bounds.insetAll(30, 10, 42, 10), image.bounds, 2, 1.0);
					image.setPixels(fftDataArray, Rect(index%bounds.width, 0, 1, (tobin - frombin + 1)));
				}); 
				view.refresh;
			});

		crosshairCalcFunc = {|view, mx, my|
			mouseX = (mx-1.5).clip(30, 30+view.bounds.width);
			mouseY = (my-1.5).clip(10, 10+view.bounds.height); 
			mXIndex = (mouseX-30).round(1); 
			mYIndex = ((view.bounds.height+10)-mouseY).round(1); 
			freq = binfreqs[mYIndex.linlin(0, view.bounds.height, frombin*2, tobin*2).floor(1)].round(0.01);
			view.refresh;
		};
		
		startbutt = Button(window, Rect(545, 10, 36, 16))
			.states_([["Power", Color.black, Color.clear], 
					 ["Power", Color.black, Color.green.alpha_(0.2)]])
			.action_({ arg view; if(view.value == 1, { this.startruntask }, { this.stopruntask }) })
			.font_(font)
			.resize_(3)
			.canFocus_(false);

		StaticText(window, Rect(545, 42, 36, 16))
			.font_(GUI.font.new("Helvetica", 10))
			.resize_(3)
			.string_("BusIn");

		NumberBox(window, Rect(545, 60, 36, 16))
			.font_(font)
			.resize_(3)
			.action_({ arg view;
				view.value_(view.value.asInteger.clip(0, server.options.numAudioBusChannels));
				this.inbus_(view.value);
			})
			.value_(0);

		StaticText(window, Rect(545, 82, 36, 16))
			.font_(font)
			.resize_(3)
			.string_("int");

		NumberBox(window, Rect(545, 100, 36, 16))
			.font_(font)
			.resize_(3)
			.action_({ arg view;
				view.value_(view.value.asInteger.clip(1, 40));
				this.intensity_(view.value);
			})
			.value_(5);

		StaticText(window, Rect(545, 122, 36, 16))
			.font_(font)
			.resize_(3)
			.string_("winsize");

		PopUpMenu(window, Rect(545, 140, 36, 16))
			.items_(["256", "512", "1024", "2048"])
			.value_(2)
			.resize_(3)
			.font_(Font("Helvetica", 9))
			.background_(Color.white)
			.canFocus_(false)
			.action_({ arg ch; var inbus;
				this.setBufSize_( ch.items[ch.value].asInteger, startbutt.value.booleanValue );
				rangeslider.lo_(0).hi_(1).doAction;
			});

		highfreq = NumberBox(window, Rect(545, 170, 36, 16))
			.font_(font)
			.resize_(3)
			.action_({ arg view;
				var rangedval; 
				rangedval = view.value.clip(lowfreq.value, (server.sampleRate/2));
				view.value_( rangedval.nearestInList(binfreqs).round(1) );
				rangeslider.hi_( view.value / (server.sampleRate/2) );
			})
			.value_(22050);

		rangeslider = RangeSlider(window, Rect(550, 192, 26, 80))
			.lo_(0.0)
			.range_(1.4)
			.resize_(3)
			.knobColor_(Color(0.40392156862745, 0.58039215686275, 0.40392156862745, 1.0))
			.action_({ |slider|
				var lofreq, hifreq, spec;
				lofreq = (slider.lo*(server.sampleRate/2)).nearestInList(binfreqs).round(1);
				hifreq = (slider.hi*(server.sampleRate/2)).nearestInList(binfreqs).round(1);
				lowfreq.value_( lofreq );
				highfreq.value_( hifreq );
				frombin = max( (slider.lo * (bufSize/2)).round(0.1), 0);
				tobin = min( (slider.hi * (bufSize/2)).round(0.1), bufSize/2 -1);
				spec = [lofreq, hifreq].asSpec;
				freqtextarray.do({|view, i|
					var val;
					val = ((spec.map(0.1*(10-i))/1000).round(0.1)).asString; 
					if(val.contains(".").not, { val = val++".0"});
					view.string_(val); 
				});
				this.setWindowImage;
				userview.backgroundImage_(image, 10);
			});

		lowfreq = NumberBox(window, Rect(545, 278, 36, 16))
			.font_(font)
			.resize_(3)
			.action_({ arg view;
				var rangedval; 
				rangedval = view.value.clip(0, highfreq.value);
				view.value_( rangedval.nearestInList(binfreqs).round(1) );
				rangeslider.lo_( view.value / (server.sampleRate/2) );
			})
			.value_(0);
		
		// this needs fixing - (will be fixed when .onResize_({}) will be available in SCWindow
		freqtextarray = Array.fill(11, { arg i;
			StaticText(window, Rect(5, 10+(i*(window.bounds.height/11)), 20, 10))
				.string_(
					if(((((server.sampleRate/2) / 10000)*(10-i)).round(0.1)).asString.contains("."), {
						((((server.sampleRate/2) / 10000)*(10-i)).round(0.1)).asString;
					},{
						((((server.sampleRate/2) / 10000)*(10-i)).round(0.1)).asString++".0";
					});				
				)
				.font_(Font("Helvetica", 9))
				.align_(1);
		});
		
		CmdPeriod.add( cper = { 
			if(startbutt.value == 1, {
				startbutt.valueAction_(0);
				AppClock.sched(0.5, { startbutt.valueAction_(1) });
			});
		 });
		
		window.onClose_({
			image.free;
			try{ fftSynth.free };
			try{ fftbuf.free };
			scopeOpen = false; 
			this.stopruntask;
			CmdPeriod.remove(cper);
		}).front;
	}
	
	setWindowImage { // overwrite the superclass method
		if(image.isNil.not, {image.free});
		image = SCImage.color(bounds.width, (tobin - frombin + 1), background);
		index = 0;
	}

	start {
		{startbutt.valueAction_(1)}.defer(0.5);
	}
	
	stop {
		{startbutt.valueAction_(0)}.defer(0.5);
	}
	
	crosshairColor_{arg argcolor;
		crosshaircolor = argcolor;
	}
	
	background_ {arg backgroundarg;
		background = backgroundarg;
		image.free;
		image = SCImage.color(window.bounds.width, bufSize/2, background);
		this.recalcGradient;
		userview.backgroundImage_(image, 10);
	}
}

+ Function {
	spectrogram {
		this.play;
		if(SpectrogramWindow.scopeOpen != true, {
			SpectrogramWindow.new.start;
		});
	}
}
