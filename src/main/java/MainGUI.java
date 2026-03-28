import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainGUI extends Application {
	@Override
	public void start(Stage primaryStage) throws Exception {
		FXMLLoader loader = new FXMLLoader(getClass().getResource("/view.fxml"));
		Parent root = loader.load();

		primaryStage.setTitle("BMS Telemetry Dashboard");
		primaryStage.setScene(new Scene(root));
		primaryStage.show();

		primaryStage.setOnCloseRequest(event -> System.exit(0));
	}

	public static void main(String[] args) {
		launch(args);
	}
}
