import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the frames of a demo video from a storyboard of plain text. JDK only (Java 25), no dependencies, no FFmpeg needed here:
 * the frames are encoded afterwards by FFmpeg (through Docker, see AGENTS.md, "Making the demo videos").
 *
 * <pre>
 *   java scripts/media/RenderFrames.java storyboard.txt frames-folder
 * </pre>
 *
 * Storyboard: scenes in order. A scene starts with {@code @scene <seconds> <slide|type>}, then {@code @caption <text>} (one line, shown
 * at the bottom), then the body. {@code slide} shows the body at once; {@code type} reveals it line by line (like a console that is
 * being typed into) and scrolls when it is longer than the screen. The text of a {@code type} scene is meant to be the captured output
 * of a real run, never invented. {@code @include <file>} (relative to the storyboard) puts a captured file into the body. Frames are
 * written as {@code frame-00000.png}, at 10 per second, 1280 by 720.
 */
public final class RenderFrames {

    static final int W = 1280;
    static final int H = 720;
    static final int FPS = 10;
    static final int COLS = 118;
    static final int ROWS = 25;

    record Scene(double seconds, boolean type, String caption, List<String> body) {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: java scripts/media/RenderFrames.java <storyboard> <frames-folder>");
            System.exit(2);
        }
        System.setProperty("java.awt.headless", "true");
        Path storyboard = Path.of(args[0]).toAbsolutePath();
        Path out = Files.createDirectories(Path.of(args[1]));
        int frame = 0;
        for (Scene scene : parse(storyboard)) {
            int frames = (int) Math.round(scene.seconds() * FPS);
            for (int i = 0; i < frames; i++) {
                double progress = frames <= 1 ? 1 : Math.min(1.0, (double) i / Math.max(1, frames * 0.8));
                ImageIO.write(draw(scene, progress), "png", out.resolve("frame-%05d.png".formatted(frame++)).toFile());
            }
        }
        System.out.println("wrote %d frames (%.1f seconds) to %s".formatted(frame, frame / (double) FPS, out));
    }

    static List<Scene> parse(Path storyboard) throws IOException {
        List<Scene> scenes = new ArrayList<>();
        double seconds = 0;
        boolean type = false;
        String caption = "";
        List<String> body = null;
        for (String line : Files.readAllLines(storyboard, StandardCharsets.UTF_8)) {
            if (line.startsWith("@scene ")) {
                if (body != null) {
                    scenes.add(new Scene(seconds, type, caption, body));
                }
                String[] parts = line.split("\\s+");
                seconds = Double.parseDouble(parts[1]);
                type = parts[2].equals("type");
                caption = "";
                body = new ArrayList<>();
            } else if (line.startsWith("@caption ")) {
                caption = line.substring("@caption ".length());
            } else if (line.startsWith("@include ")) {
                body.addAll(Files.readAllLines(storyboard.resolveSibling(line.substring("@include ".length()).strip()), StandardCharsets.UTF_8));
            } else if (body != null) {
                body.add(line);
            }
        }
        if (body != null) {
            scenes.add(new Scene(seconds, type, caption, body));
        }
        return scenes;
    }

    static BufferedImage draw(Scene scene, double progress) {
        BufferedImage image = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x14, 0x17, 0x1c));
        g.fillRect(0, 0, W, H);
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 17);
        g.setFont(mono);
        FontMetrics m = g.getFontMetrics();
        int lineHeight = m.getHeight();
        List<String> lines = wrap(scene.body());
        int shown = scene.type() ? (int) Math.ceil(lines.size() * progress) : lines.size();
        int from = Math.max(0, shown - ROWS);
        g.setColor(scene.type() ? new Color(0xd6, 0xde, 0xeb) : new Color(0xf2, 0xf5, 0xf9));
        int y = 40;
        for (int i = from; i < shown; i++) {
            String line = lines.get(i);
            g.setColor(colorOf(line, scene.type()));
            g.drawString(line, 24, y);
            y += lineHeight;
        }
        // the caption bar
        g.setColor(new Color(0x24, 0x2b, 0x36));
        g.fillRect(0, H - 64, W, 64);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        g.setColor(new Color(0xff, 0xd8, 0x6b));
        String caption = scene.caption();
        g.drawString(caption.length() > 120 ? caption.substring(0, 119) + "..." : caption, 24, H - 26);
        g.dispose();
        return image;
    }

    private static Color colorOf(String line, boolean type) {
        if (!type) {
            return new Color(0xf2, 0xf5, 0xf9);
        }
        if (line.startsWith("$> ") || line.startsWith("> ")) {
            return new Color(0x8a, 0xe2, 0x34);
        }
        if (line.contains("[FAILED]") || line.contains("FAIL ")) {
            return new Color(0xff, 0x7b, 0x72);
        }
        if (line.contains("[warning]")) {
            return new Color(0xe3, 0xb3, 0x41);
        }
        return new Color(0xd6, 0xde, 0xeb);
    }

    private static List<String> wrap(List<String> body) {
        List<String> lines = new ArrayList<>();
        for (String line : body) {
            String text = line.replace("\t", "    ").replaceAll("\\p{Cntrl}", "");
            while (text.length() > COLS) {
                lines.add(text.substring(0, COLS));
                text = "    " + text.substring(COLS);
            }
            lines.add(text);
        }
        return lines;
    }
}
