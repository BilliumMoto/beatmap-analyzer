package lt.ekgame.beatmap_analyzer.parser;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lt.ekgame.beatmap_analyzer.Gamemode;
import lt.ekgame.beatmap_analyzer.beatmap.*;
import lt.ekgame.beatmap_analyzer.parser.hitobjects.*;

public class BeatmapParser {
	
	private static final Pattern PART_TAG = Pattern.compile("^\\[(\\w+)\\]");
	private static final String[] REQUIRED_TAGS = {"General", "Metadata", "TimingPoints", "Difficulty", "Events", "HitObjects"};
	
	private static final Map<Gamemode, HitObjectParser<?>> PARSERS = new HashMap<>();
	
	static {
		PARSERS.put(Gamemode.OSU,   new OsuParser());
		PARSERS.put(Gamemode.TAIKO, new TaikoParser());
		//PARSERS.put(Gamemode.CATCH, new CatchParser());
		//PARSERS.put(Gamemode.MANIA, new ManiaParser());
	}
	
	@SuppressWarnings("unchecked")
	public <T extends Beatmap> T parse(File file, Class<T> klass) throws BeatmapException, FileNotFoundException {
		return (T) parse(file);
	}
	
	public Beatmap parse(File file) throws FileNotFoundException, BeatmapException {
		return parse(new FileInputStream(file));
	}
	
	@SuppressWarnings("unchecked")
	public <T extends Beatmap> T parse(String string, Class<T> klass) throws BeatmapException {
		return (T) parse(string);
	}
	
	public Beatmap parse(String string) throws BeatmapException {
		return parse(new ByteArrayInputStream(string.getBytes(StandardCharsets.UTF_8)));
	}
	
	@SuppressWarnings("unchecked")
	public <T extends Beatmap> T parse(InputStream stream, Class<T> klass) throws BeatmapException {
		return (T) parse(stream);
	}
	
	public Beatmap parse(InputStream stream) throws BeatmapException {
		try (Scanner scanner = new Scanner(stream)){
			Map<String, FilePart> parts = new HashMap<>();
			
			String tag = "Header";
			List<String> lines = new ArrayList<>();
			while (scanner.hasNextLine()) {
				String line = scanner.nextLine().trim();
				Matcher matcher = PART_TAG.matcher(line);
				if (matcher.find()) {
					parts.put(tag, new FilePart(tag, lines));
					lines = new ArrayList<>();
					tag = matcher.group(1);
				}
				else if (!line.isEmpty() && !line.startsWith("//")){
					lines.add(line);
				}
			}
			parts.put(tag, new FilePart(tag, lines));
			
			for (String reqiredTag : REQUIRED_TAGS)
				if (!parts.containsKey(reqiredTag))
					throw new BeatmapException("Couldn't find required \"" + reqiredTag + "\" tag found.");
			
			BeatmapGenerals generalSettings = new BeatmapGenerals(parts.get("General"));
			HitObjectParser<?> parser = PARSERS.get(generalSettings.getGamemode());
			// TODO: parse other gamemodes
			if (parser == null)
				return null;
			
			BeatmapMetadata metadata = new BeatmapMetadata(parts.get("Metadata"));
			BeatmapDifficulties difficulties = new BeatmapDifficulties(parts.get("Difficulty"));
			BeatmapEditorState editorState = null;
			
			// Older formats don't have the "Editor" tag
			if (parts.containsKey("Editor"))
				editorState = new BeatmapEditorState(parts.get("Editor"));
			
			List<BreakPeriod> breaks = parseBreaks(parts.get("Events"));
			List<TimingPoint> timingPoints = parseTimePoints(parts.get("TimingPoints"));
			List<String> rawObjects = parts.get("HitObjects").getLines();
			
			return parser.buildBeatmap(generalSettings, editorState, metadata, difficulties, breaks, timingPoints, rawObjects);
		}
	}
	
	private static Function<String, TimingPoint> timingPointMapper = line -> {
		String[] args = line.split(",");
		
		double timestamp = Double.parseDouble(args[0].trim());
		double beatLength = Double.parseDouble(args[1].trim());
		int meter = 4;
		int sampleType = 0;
		int sampleSet = 0;
		int volume = 100;
		boolean isInherited = false;
		boolean isKiai = false;
		
		if (args.length > 2) {
			meter = Integer.parseInt(args[2].trim());
			sampleType = Integer.parseInt(args[3].trim());
			sampleSet = Integer.parseInt(args[4].trim());
			volume = Integer.parseInt(args[5].trim());
		}
		
		if (args.length >= 7)
			isInherited = Integer.parseInt(args[6].trim()) == 0;
		if (args.length >= 8)
			isKiai = Integer.parseInt(args[7].trim()) == 0;
		
		return new TimingPoint(timestamp, beatLength, meter, sampleType, sampleSet, volume, isInherited, isKiai);
	};
	
	private List<TimingPoint> parseTimePoints(FilePart part) {
		return part.getLines().stream()
			.map(timingPointMapper)
			.sorted((o1, o2) -> (int)(o1.getTimestamp()- o2.getTimestamp()))
			.collect(Collectors.toList());
	}
	
	private static Function<String, BreakPeriod> breakPeriodMapper = line -> {
		String[] args = line.split(",");
		return new BreakPeriod(
			Integer.parseInt(args[1].trim()),
			Integer.parseInt(args[2].trim())
		);
	};
	
	private List<BreakPeriod> parseBreaks(FilePart part) {
		return part.getLines().stream()
			.filter(o->o.trim().startsWith("2,"))
			.map(breakPeriodMapper)
			.collect(Collectors.toList());
	}
}
