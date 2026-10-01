package org.arghyam.jalsoochak.tenant.controller.tenant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Map;

import org.arghyam.jalsoochak.tenant.dto.internal.ConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.SimpleConfigValueDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.TenantLogoResult;
import org.arghyam.jalsoochak.tenant.dto.response.TenantConfigResponseDTO;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;
import org.arghyam.jalsoochak.tenant.exception.ResourceNotFoundException;
import org.arghyam.jalsoochak.tenant.exception.StorageException;
import org.arghyam.jalsoochak.tenant.service.TenantManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TenantBrandingController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("Tenant Branding Controller Tests")
class TenantBrandingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantManagementService tenantManagementService;

    @Nested
    @DisplayName("Set Tenant Logo")
    class SetTenantLogoTests {

        @Test
        void setTenantLogo_FileSource_Success() throws Exception {
            Integer tenantId = 1;
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", new byte[]{1, 2, 3});

            Map<TenantConfigKeyEnum, ConfigValueDTO> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_LOGO, new SimpleConfigValueDTO("logos/1/uuid.png"));
            TenantConfigResponseDTO response = TenantConfigResponseDTO.builder()
                    .tenantId(tenantId).configs(configs).build();

            when(tenantManagementService.setTenantLogo(eq(tenantId), any())).thenReturn(response);

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/logo", tenantId)
                    .file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("Logo set successfully"))
                    .andExpect(jsonPath("$.data.configs.TENANT_LOGO.value").value("logos/1/uuid.png"));

            verify(tenantManagementService).setTenantLogo(eq(tenantId), any());
        }

        @Test
        void setTenantLogo_UrlSource_Success() throws Exception {
            Integer tenantId = 1;
            String externalUrl = "https://cdn.example.com/logo.png";

            Map<TenantConfigKeyEnum, ConfigValueDTO> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_LOGO, new SimpleConfigValueDTO(externalUrl));
            TenantConfigResponseDTO response = TenantConfigResponseDTO.builder()
                    .tenantId(tenantId).configs(configs).build();

            when(tenantManagementService.setTenantLogo(eq(tenantId), any())).thenReturn(response);

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/logo", tenantId)
                    .param("url", externalUrl))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Logo set successfully"))
                    .andExpect(jsonPath("$.data.configs.TENANT_LOGO.value").value(externalUrl));

            verify(tenantManagementService).setTenantLogo(eq(tenantId), any());
        }

        @Test
        void setTenantLogo_NeitherProvided_Returns400() throws Exception {
            Integer tenantId = 1;

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/logo", tenantId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("Either a logo file or an external URL must be provided."));

            verify(tenantManagementService, never()).setTenantLogo(any(), any());
        }

        @Test
        void setTenantLogo_BothProvided_Returns400() throws Exception {
            Integer tenantId = 1;
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", new byte[]{1, 2, 3});

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/logo", tenantId)
                    .file(file)
                    .param("url", "https://cdn.example.com/logo.png"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message")
                            .value("Provide either a logo file or an external URL, not both."));

            verify(tenantManagementService, never()).setTenantLogo(any(), any());
        }

        @Test
        void setTenantLogo_TenantNotFound_Returns404() throws Exception {
            Integer tenantId = 999;
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", new byte[]{1, 2, 3});

            when(tenantManagementService.setTenantLogo(eq(tenantId), any()))
                    .thenThrow(new ResourceNotFoundException("Tenant not found"));

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/logo", tenantId)
                    .file(file))
                    .andExpect(status().isNotFound());
        }

        @Test
        void setTenantLogo_UnsupportedType_Returns400() throws Exception {
            Integer tenantId = 1;
            MockMultipartFile file = new MockMultipartFile(
                    "file", "doc.pdf", "application/pdf", new byte[]{1, 2, 3});

            when(tenantManagementService.setTenantLogo(eq(tenantId), any()))
                    .thenThrow(new IllegalArgumentException("Unsupported logo file type: application/pdf"));

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/logo", tenantId)
                    .file(file))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Unsupported logo file type: application/pdf"));
        }

        @Test
        void setTenantLogo_EmptyFile_Returns400() throws Exception {
            Integer tenantId = 1;
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", new byte[0]);

            when(tenantManagementService.setTenantLogo(eq(tenantId), any()))
                    .thenThrow(new IllegalArgumentException("Logo file must not be empty"));

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/logo", tenantId)
                    .file(file))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void setTenantLogo_StorageFailure_Returns500() throws Exception {
            Integer tenantId = 1;
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.png", "image/png", new byte[]{1, 2, 3});

            when(tenantManagementService.setTenantLogo(eq(tenantId), any()))
                    .thenThrow(new StorageException("Upload failed: bucket unreachable"));

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/logo", tenantId)
                    .file(file))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("File storage operation failed"));
        }

        @Test
        void setTenantLogo_JpegFile_Success() throws Exception {
            Integer tenantId = 1;
            MockMultipartFile file = new MockMultipartFile(
                    "file", "logo.jpg", "image/jpeg", new byte[]{1, 2, 3});

            Map<TenantConfigKeyEnum, ConfigValueDTO> configs = new HashMap<>();
            configs.put(TenantConfigKeyEnum.TENANT_LOGO, new SimpleConfigValueDTO("logos/1/uuid.jpg"));
            TenantConfigResponseDTO response = TenantConfigResponseDTO.builder()
                    .tenantId(tenantId).configs(configs).build();

            when(tenantManagementService.setTenantLogo(eq(tenantId), any())).thenReturn(response);

            mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/tenants/{tenantId}/logo", tenantId)
                    .file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.configs.TENANT_LOGO.value").value("logos/1/uuid.jpg"));
        }
    }

    @Nested
    @DisplayName("Get Tenant Logo")
    class GetTenantLogoTests {

        @Test
        void getTenantLogo_managedObject_returnsImage() throws Exception {
            Integer tenantId = 1;
            when(tenantManagementService.resolveTenantLogo(tenantId))
                    .thenReturn(new TenantLogoResult.Managed(
                            new ByteArrayInputStream("fake-png-bytes".getBytes()), "image/png"));

            var mvcResult = mockMvc.perform(get("/api/v1/tenants/{tenantId}/logo", tenantId))
                    .andExpect(request().asyncStarted())
                    .andReturn();

            mockMvc.perform(asyncDispatch(mvcResult))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("image/png"));
        }

        @Test
        void getTenantLogo_externalUrl_redirects() throws Exception {
            Integer tenantId = 1;
            String externalUrl = "https://cdn.example.com/logo.png";
            when(tenantManagementService.resolveTenantLogo(tenantId))
                    .thenReturn(new TenantLogoResult.External(externalUrl));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/logo", tenantId))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", externalUrl));
        }

        @Test
        void getTenantLogo_logoNotConfigured_returns404() throws Exception {
            Integer tenantId = 1;
            when(tenantManagementService.resolveTenantLogo(tenantId))
                    .thenThrow(new ResourceNotFoundException("Logo not configured for tenant [id=1]"));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/logo", tenantId))
                    .andExpect(status().isNotFound());
        }

        @Test
        void getTenantLogo_storageFailure_returns500() throws Exception {
            Integer tenantId = 1;
            when(tenantManagementService.resolveTenantLogo(tenantId))
                    .thenThrow(new StorageException("Storage unreachable"));

            mockMvc.perform(get("/api/v1/tenants/{tenantId}/logo", tenantId))
                    .andExpect(status().isInternalServerError());
        }
    }
}
