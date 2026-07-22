package com.petclinic.rag.service.extraction;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

@Component
public class ImageOcrExtractor implements TextExtractor {

    private final Tesseract tesseract;

    public ImageOcrExtractor(@Value("${tesseract.datapath}") String tessDataPath) {
        this.tesseract = new Tesseract();
        this.tesseract.setDatapath(tessDataPath);
        this.tesseract.setLanguage("eng");
    }

    @Override
    public String extract(InputStream inputStream, String filename) {
        try {
            BufferedImage image = ImageIO.read(inputStream);
            if (image == null) {
                throw new RuntimeException("Could not decode image file: " + filename);
            }
            return tesseract.doOCR(image);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read image file: " + filename, e);
        } catch (TesseractException e) {
            throw new RuntimeException("OCR failed for file: " + filename, e);
        }
    }

    @Override
    public boolean supports(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".tiff");
    }
}