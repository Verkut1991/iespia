package com.example.iespia;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.iespia.api.ServicioApi;
import com.example.iespia.api.ClienteRetrofit;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// Actividad para escanear (buscar) un rostro y compararlo con los objetivos registrados.
// A diferencia de CamaraActivity, esta NO crea un nuevo objetivo, sino que BUSCA coincidencias.
public class ScanActivity extends AppCompatActivity {

    private static final String TAG = "ActividadEscaneo"; // Etiqueta para logs.
    private static final int CODIGO_SOLICITUD_PERMISOS = 10; // Codigo de solicitud de permisos.
    private static final String[] PERMISOS_REQUERIDOS = { Manifest.permission.CAMERA }; // Permisos necesarios.

    private ImageCapture capturaImagen; // Caso de uso de CameraX para capturar imagen.
    private File directorioSalida; // Directorio de salida temporal.
    private ExecutorService ejecutorCamara; // Hilo de trabajo para procesar imagenes.
    private String tokenAuth; // Token de autenticacion JWT.
    private ServicioApi servicioApi; // Interfaz API (Retrofit).
    private CameraSelector selectorCamara = CameraSelector.DEFAULT_BACK_CAMERA; // Selector de camara (trasera por
                                                                                // defecto).

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scan); // Carga el layout del escaner.

        // Recibe el token de la actividad anterior.
        tokenAuth = getIntent().getStringExtra("authToken");
        // Obtiene la instancia de la API.
        servicioApi = ClienteRetrofit.obtenerServicioApi();

        // Comprueba y solicita permisos de camara.
        if (todosPermisosOtorgados()) {
            iniciarCamara();
        } else {
            ActivityCompat.requestPermissions(this, PERMISOS_REQUERIDOS, CODIGO_SOLICITUD_PERMISOS);
        }

        // Boton para iniciar el escaneo (capturar foto y enviar al servidor).
        Button botonEscaneo = findViewById(R.id.scan_button);
        botonEscaneo.setOnClickListener(v -> tomarFoto());

        // Boton para cambiar entre camara trasera y delantera.
        ImageButton btnSwitchCamera = findViewById(R.id.btnSwitchCamera);
        btnSwitchCamera.setOnClickListener(v -> {
            if (selectorCamara == CameraSelector.DEFAULT_BACK_CAMERA) {
                selectorCamara = CameraSelector.DEFAULT_FRONT_CAMERA;
            } else {
                selectorCamara = CameraSelector.DEFAULT_BACK_CAMERA;
            }
            iniciarCamara(); // Reinicia la camara con el nuevo selector.
        });

        directorioSalida = obtenerDirectorioSalida();
        ejecutorCamara = Executors.newSingleThreadExecutor();
    }

    // Captura una foto para el escaneo de reconocimiento facial.
    private void tomarFoto() {
        if (capturaImagen == null)
            return;

        // Crea un archivo temporal para la foto.
        File archivoFoto = new File(directorioSalida, UUID.randomUUID().toString() + ".jpg");
        ImageCapture.OutputFileOptions opcionesSalida = new ImageCapture.OutputFileOptions.Builder(archivoFoto).build();

        // Ejecuta la captura.
        capturaImagen.takePicture(opcionesSalida, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults resultadoArchivo) {
                        Toast.makeText(ScanActivity.this, "Iniciando escaneo...", Toast.LENGTH_SHORT).show();
                        // Envia la foto al servidor para reconocimiento.
                        realizarEscaneo(archivoFoto);
                        finish(); // Regresa a MainActivity.
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException excepcion) {
                        Log.e(TAG, "Fallo en la captura: " + excepcion.getMessage(), excepcion);
                    }
                });
    }

    // Realiza el escaneo: convierte la foto a Base64 y la envia al endpoint de
    // reconocimiento.
    private void realizarEscaneo(File archivoFoto) {
        ejecutorCamara.execute(() -> {
            try {
                // Decodifica el archivo a Bitmap.
                Bitmap bitmap = BitmapFactory.decodeFile(archivoFoto.getAbsolutePath());
                // Comprime a JPEG al 70% y convierte a Base64.
                ByteArrayOutputStream flujoSalida = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, flujoSalida);
                byte[] arrayBytes = flujoSalida.toByteArray();
                String imagenBase64 = Base64.encodeToString(arrayBytes, Base64.DEFAULT);

                // Crea la peticion usando el DTO PeticionEscaneo con la imagen en Base64.
                ServicioApi.PeticionEscaneo peticion = new ServicioApi.PeticionEscaneo(imagenBase64);

                // Llama al endpoint de escaneo del servidor.
                // El servidor ejecutara el script Python de reconocimiento facial.
                servicioApi.escanearRostro(tokenAuth, peticion).enqueue(new Callback<ServicioApi.RespuestaEscaneo>() {
                    @Override
                    public void onResponse(Call<ServicioApi.RespuestaEscaneo> call,
                            Response<ServicioApi.RespuestaEscaneo> response) {
                        if (response.isSuccessful() && response.body() != null) {
                            Log.d(TAG, "Peticion de escaneo enviada. ID: " + response.body().scanId);
                        } else {
                            Log.e(TAG, "Fallo en la peticion de escaneo: " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(Call<ServicioApi.RespuestaEscaneo> call, Throwable t) {
                        Log.e(TAG, "Error en la peticion de escaneo: " + t.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error procesando imagen para el escaneo", e);
            }
        });
    }

    // Inicializa la previsualizacion de la camara (idéntico a CamaraActivity).
    private void iniciarCamara() {
        ListenableFuture<ProcessCameraProvider> futuroProveedorCamara = ProcessCameraProvider.getInstance(this);

        futuroProveedorCamara.addListener(() -> {
            try {
                ProcessCameraProvider proveedorCamara = futuroProveedorCamara.get();

                // Configura la vista previa.
                Preview vistaPrevia = new Preview.Builder().build();
                vistaPrevia.setSurfaceProvider(((PreviewView) findViewById(R.id.viewFinder)).getSurfaceProvider());

                // Configura la captura de imagen.
                capturaImagen = new ImageCapture.Builder().build();

                // Selecciona la camara (según el estado del selector).
                // selectorCamara ya esta definido como miembro de la clase.

                // Vincula los casos de uso al ciclo de vida de la Activity.
                proveedorCamara.unbindAll();
                proveedorCamara.bindToLifecycle(this, selectorCamara, vistaPrevia, capturaImagen);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error al vincular la camara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // Verifica si todos los permisos necesarios fueron concedidos.
    private boolean todosPermisosOtorgados() {
        for (String permiso : PERMISOS_REQUERIDOS) {
            if (ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // Responde al resultado de la solicitud de permisos.
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CODIGO_SOLICITUD_PERMISOS) {
            if (todosPermisosOtorgados()) {
                iniciarCamara();
            } else {
                Toast.makeText(this, "Permisos no concedidos por el usuario.", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    // Obtiene el directorio para guardar fotos temporales.
    private File obtenerDirectorioSalida() {
        File[] directoriosMedia = getExternalMediaDirs();
        if (directoriosMedia != null && directoriosMedia.length > 0) {
            File dirMedia = new File(directoriosMedia[0], getString(R.string.app_name));
            if (dirMedia.exists() || dirMedia.mkdirs())
                return dirMedia;
        }
        return getFilesDir();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ejecutorCamara.shutdown(); // Libera el hilo de la camara.
    }
}
