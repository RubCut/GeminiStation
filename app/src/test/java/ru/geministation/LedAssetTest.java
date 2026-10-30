package ru.geministation;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.junit.Test;
import org.w3c.dom.Node;
import static org.junit.Assert.*;

public class LedAssetTest {
    private File asset(String name) {
        File file = new File("src/main/assets/led/" + name + ".gif");
        return file.exists() ? file : new File("app/src/main/assets/led/" + name + ".gif");
    }
    private int duration(Node node) {
        if (node.getNodeName().equals("GraphicControlExtension"))
            return Integer.parseInt(node.getAttributes().getNamedItem("delayTime").getNodeValue()) * 10;
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            int value = duration(child);
            if (value != 0) return value;
        }
        return 0;
    }
    @Test public void animationsAreShortFiniteAndFitTheMatrix() throws Exception {
        for (String name : new String[]{"listening", "thinking", "speaking", "clear"}) {
            File file = asset(name);
            assertTrue(name, file.isFile());
            // A GIF with no NETSCAPE loop extension can expire and hand the display back.
            assertFalse(name, new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1).contains("NETSCAPE2.0"));
            try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
                ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
                try {
                    reader.setInput(in);
                    int count = reader.getNumImages(true), total = 0;
                    assertTrue(count >= 2);
                    for (int i = 0; i < count; i++) {
                        assertEquals(25, reader.getWidth(i)); assertEquals(16, reader.getHeight(i));
                        total += duration(reader.getImageMetadata(i).getAsTree("javax_imageio_gif_image_1.0"));
                    }
                    assertTrue(name + " duration=" + total, total > 0 && total <= (name.equals("clear") ? 500 : 2500));
                } finally { reader.dispose(); }
            }
        }
    }
}
