package stirling.software.SPDF.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import stirling.software.SPDF.controller.api.converters.ConvertImgPDFController;
import stirling.software.SPDF.controller.api.misc.CompressController;
import stirling.software.SPDF.controller.api.misc.OCRController;
import stirling.software.SPDF.controller.api.misc.RepairController;
import stirling.software.SPDF.model.api.converters.ConvertToImageRequest;
import stirling.software.SPDF.model.api.general.MergePdfsRequest;
import stirling.software.SPDF.model.api.general.RotatePDFRequest;
import stirling.software.SPDF.model.api.misc.OptimizePdfRequest;
import stirling.software.SPDF.model.api.misc.ProcessPdfWithOcrRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.model.api.PDFFile;

class LargeFileEndpointQueueabilityTest {

    @Test
    void representativeLargeFileEndpointsShouldBeQueueable() throws Exception {
        assertQueueable(
                RotationController.class.getMethod("rotatePDF", RotatePDFRequest.class), 25);
        assertQueueable(
                MergeController.class.getMethod("mergePdfs", MergePdfsRequest.class, String.class),
                70);
        assertQueueable(
                CompressController.class.getMethod("optimizePdf", OptimizePdfRequest.class), 85);
        assertQueueable(
                OCRController.class.getMethod("processPdfWithOCR", ProcessPdfWithOcrRequest.class),
                90);
        assertQueueable(RepairController.class.getMethod("repairPdf", PDFFile.class), 75);
        assertQueueable(
                ConvertImgPDFController.class.getMethod(
                        "convertToImage", ConvertToImageRequest.class),
                80);
    }

    private void assertQueueable(Method method, int expectedWeight) {
        AutoJobPostMapping mapping = method.getAnnotation(AutoJobPostMapping.class);

        assertTrue(mapping.queueable(), method.getName() + " should be queueable");
        assertEquals(expectedWeight, mapping.resourceWeight(), method.getName() + " weight");
    }
}
