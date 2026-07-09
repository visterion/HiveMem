package com.hivemem.consumption;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PageOrienterTest {

    /** 20x30 white PNG with a black pixel at (2,3) so rotation is observable. */
    private static byte[] markedPng() throws Exception {
        BufferedImage img = new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 20, 30);
        g.dispose();
        img.setRGB(2, 3, Color.BLACK.getRGB());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    @Test
    void uprightAMeansRotation0() throws Exception {
        VisionMultiClient vm = mock(VisionMultiClient.class);
        when(vm.group(eq("documents"), anyString(), anyList()))
                .thenReturn("{\"upright\":\"A\",\"blank\":false,\"confidence\":0.99}");
        PageOrienter orienter = new PageOrienter(vm);
        PageOrienter.PageOrientation o = orienter.orient("documents", 3, markedPng());
        assertEquals(3, o.page());
        assertEquals(0, o.rotation());
        assertFalse(o.blank());
        assertEquals(0.99, o.confidence(), 1e-9);
        // exactly one call with TWO images (A + B)
        verify(vm).group(eq("documents"), anyString(), argThat(imgs -> imgs.size() == 2));
    }

    @Test
    void uprightBMeansRotation180AndFencedJsonIsTolerated() throws Exception {
        VisionMultiClient vm = mock(VisionMultiClient.class);
        when(vm.group(anyString(), anyString(), anyList()))
                .thenReturn("```json\n{\"upright\":\"B\",\"blank\":true,\"confidence\":0.7}\n```");
        PageOrienter.PageOrientation o = new PageOrienter(vm).orient("documents", 1, markedPng());
        assertEquals(180, o.rotation());
        assertTrue(o.blank());
    }

    @Test
    void failureRetriesOnceThenFallsBackToRotation0() throws Exception {
        VisionMultiClient vm = mock(VisionMultiClient.class);
        when(vm.group(anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("boom"))
                .thenThrow(new RuntimeException("boom again"));
        PageOrienter.PageOrientation o = new PageOrienter(vm).orient("documents", 5, markedPng());
        assertEquals(0, o.rotation());
        assertFalse(o.blank());
        assertEquals(0.0, o.confidence(), 1e-9);
        verify(vm, times(2)).group(anyString(), anyString(), anyList());
    }

    @Test
    void rotate180MovesTheMarkedPixel() throws Exception {
        byte[] rotated = PageOrienter.rotate180Png(markedPng());
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(rotated));
        assertEquals(20, img.getWidth());
        assertEquals(30, img.getHeight());
        // (2,3) → (width-1-2, height-1-3) = (17, 26)
        assertEquals(Color.BLACK.getRGB(), img.getRGB(17, 26));
        assertEquals(Color.WHITE.getRGB(), img.getRGB(2, 3));
    }
}
