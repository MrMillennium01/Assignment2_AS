import com.tub.GreetingRequest;
import com.tub.GreetingResponse;
import com.tub.GreetingServiceGrpc;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;

public class GreeterClient {
    static void main() {
        ManagedChannel channel = Grpc.newChannelBuilder("localhost:8980", InsecureChannelCredentials.create()).build();
        GreetingServiceGrpc.GreetingServiceBlockingStub stub = GreetingServiceGrpc.newBlockingStub(channel);
        GreetingRequest req = GreetingRequest
                .newBuilder()
                .setName("Jane Doe")
                .build();
        GreetingResponse res = stub.greet(req);
        IO.println(res.getGreeting());
    }
}
