package lk.ijse.eca.customerservice.service.impl;

import lk.ijse.eca.customerservice.dto.CustomerRequestDTO;
import lk.ijse.eca.customerservice.dto.CustomerResponseDTO;
import lk.ijse.eca.customerservice.entity.Customer;
import lk.ijse.eca.customerservice.mapper.CustomerMapper;
import lk.ijse.eca.customerservice.exception.DuplicateCustomerException;
import lk.ijse.eca.customerservice.exception.FileOperationException;
import lk.ijse.eca.customerservice.exception.CustomerNotFoundException;
import lk.ijse.eca.customerservice.repository.CustomerRepository;
import lk.ijse.eca.customerservice.service.CustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerServiceImpl implements CustomerService {

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    @Value("${app.storage.path}")
    private String storagePathStr;

    private Path storagePath;

    /**
     * Creates a new customer.
     *
     * Transaction strategy:
     *  1. Persist customer record to DB (JPA defers the INSERT until flush/commit).
     *  2. Write picture file to disk (immediate).
     *  3. If the file write fails an exception is thrown, which causes
     *     @Transactional to roll back the DB INSERT — no orphaned record.
     *  4. If the file write succeeds the method returns normally and
     *     @Transactional commits both the record and the file atomically.
     */
    @Override
    @Transactional
    public CustomerResponseDTO createCustomer(CustomerRequestDTO dto) {
        log.debug("Creating customer with NIC: {}", dto.getNic());

        if (customerRepository.existsById(dto.getNic())) {
            log.warn("Duplicate NIC detected: {}", dto.getNic());
            throw new DuplicateCustomerException(dto.getNic());
        }

        String pictureId = UUID.randomUUID().toString();

        Customer customer = customerMapper.toEntity(dto);
        customer.setPicture(pictureId);

        // DB operation first (deferred) — rolls back if file save below throws
        customerRepository.save(customer);
        log.debug("Customer persisted to DB: {}", dto.getNic());

        // Immediate file operation — failure triggers @Transactional rollback
        savePicture(pictureId, dto.getPicture());

        log.info("Customer created successfully: {}", dto.getNic());
        return customerMapper.toResponseDto(customer);
    }

    /**
     * Updates an existing customer.
     *
     * Transaction strategy:
     *  - If a new picture is supplied:
     *    1. Update DB record with new picture UUID (deferred).
     *    2. Write the new picture file (immediate).
     *    3. Failure at step 2 rolls back step 1 — old picture UUID stays in DB.
     *    4. On success, the old picture file is deleted (best-effort: a warning is
     *       logged on failure, but the transaction is NOT rolled back because DB and
     *       new file are already consistent).
     *  - If no new picture is supplied, only DB fields are updated.
     */
    @Override
    @Transactional
    public CustomerResponseDTO updateCustomer(String nic, CustomerRequestDTO dto) {
        log.debug("Updating customer with NIC: {}", nic);

        Customer customer = customerRepository.findById(nic)
                .orElseThrow(() -> {
                    log.warn("Customer not found for update: {}", nic);
                    return new CustomerNotFoundException(nic);
                });

        String oldPictureId = customer.getPicture();
        boolean pictureChanged = dto.getPicture() != null && !dto.getPicture().isEmpty();
        String newPictureId = pictureChanged ? UUID.randomUUID().toString() : oldPictureId;

        customerMapper.updateEntity(dto, customer);
        customer.setPicture(newPictureId);

        // DB update (deferred) — rolls back if new file save below throws
        customerRepository.save(customer);
        log.debug("Customer updated in DB: {}", nic);

        if (pictureChanged) {
            // Save new picture — failure triggers @Transactional rollback
            savePicture(newPictureId, dto.getPicture());
            // Remove old picture — best-effort; DB and new file are already consistent
            tryDeletePicture(oldPictureId);
        }

        log.info("Customer updated successfully: {}", nic);
        return customerMapper.toResponseDto(customer);
    }

    /**
     * Deletes a customer.
     *
     * Transaction strategy:
     *  1. Remove customer record from DB (JPA defers the DELETE until flush/commit).
     *  2. Delete picture file from disk (immediate).
     *  3. If the file delete fails an exception is thrown, which causes
     *     @Transactional to roll back the DB DELETE — neither the record
     *     nor the file is removed.
     *  4. If the file delete succeeds the method returns normally and
     *     @Transactional commits, removing the record from the DB.
     */
    @Override
    @Transactional
    public void deleteCustomer(String nic) {
        log.debug("Deleting customer with NIC: {}", nic);

        Customer customer = customerRepository.findById(nic)
                .orElseThrow(() -> {
                    log.warn("Customer not found for deletion: {}", nic);
                    return new CustomerNotFoundException(nic);
                });

        String pictureId = customer.getPicture();

        // DB deletion (deferred) — rolls back if file delete below throws
        customerRepository.delete(customer);
        log.debug("Customer marked for deletion in DB: {}", nic);

        // Immediate file deletion — failure triggers @Transactional rollback
        deletePicture(pictureId);

        log.info("Customer deleted successfully: {}", nic);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerResponseDTO getCustomer(String nic) {
        log.debug("Fetching customer with NIC: {}", nic);
        return customerRepository.findById(nic)
                .map(customerMapper::toResponseDto)
                .orElseThrow(() -> {
                    log.warn("Customer not found: {}", nic);
                    return new CustomerNotFoundException(nic);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerResponseDTO> getAllCustomers() {
        log.debug("Fetching all customers");
        List<CustomerResponseDTO> customers = customerRepository.findAll()
                .stream()
                .map(customerMapper::toResponseDto)
                .collect(Collectors.toList());
        log.debug("Fetched {} customers", customers.size());
        return customers;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] getCustomerPicture(String nic) {
        log.debug("Fetching picture for customer NIC: {}", nic);
        Customer customer = customerRepository.findById(nic)
                .orElseThrow(() -> {
                    log.warn("Customer not found: {}", nic);
                    return new CustomerNotFoundException(nic);
                });
        Path filePath = storagePath().resolve(customer.getPicture());
        try {
            return Files.readAllBytes(filePath);
        } catch (IOException e) {
            log.error("Failed to read picture for customer: {}", nic, e);
            throw new FileOperationException("Failed to read picture for customer: " + nic, e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Path storagePath() {
        if (storagePath == null) {
            storagePath = Paths.get(storagePathStr);
        }
        try {
            Files.createDirectories(storagePath);
        } catch (IOException e) {
            throw new FileOperationException(
                    "Failed to create storage directory: " + storagePath.toAbsolutePath(), e);
        }
        return storagePath;
    }

    private void savePicture(String pictureId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileOperationException("Picture file must not be empty");
        }
        Path filePath = storagePath().resolve(pictureId);
        try {
            Files.write(filePath, file.getBytes());
            log.debug("Picture saved: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to save picture: {}", filePath, e);
            throw new FileOperationException("Failed to save picture file: " + pictureId, e);
        }
    }

    private void deletePicture(String pictureId) {
        Path filePath = storagePath().resolve(pictureId);
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                log.debug("Picture deleted: {}", filePath);
            } else {
                log.warn("Picture file not found on disk (already removed?): {}", filePath);
            }
        } catch (IOException e) {
            log.error("Failed to delete picture: {}", filePath, e);
            throw new FileOperationException("Failed to delete picture file: " + pictureId, e);
        }
    }

    private void tryDeletePicture(String pictureId) {
        try {
            deletePicture(pictureId);
        } catch (FileOperationException e) {
            log.warn("Could not delete old picture file '{}'. Manual cleanup may be required.", pictureId);
        }
    }

}

