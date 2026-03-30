package lk.ijse.eca.customerservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import lk.ijse.eca.customerservice.repository.CustomerRepository;

@SpringBootTest
class CustomerServiceApplicationTests {
	@MockitoBean
	private CustomerRepository customerRepository;

	@Test
	void contextLoads() {
	}

}
