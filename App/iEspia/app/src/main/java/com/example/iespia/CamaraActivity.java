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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.net.Uri;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.iespia.api.ServicioApi;
import com.example.iespia.api.ClienteRetrofit;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

// Actividad para capturar o seleccionar la foto de un nuevo objetivo (target).
// Permite usar la camara en vivo o la galeria del dispositivo.
public class CamaraActivity extends AppCompatActivity {

    private static final String TAG = "ActividadCamara"; // Etiqueta para los logs de depuracion.
    private static final int CODIGO_SOLICITUD_PERMISOS = 10; // Codigo para la solicitud de permisos.
    private static final String[] PERMISOS_REQUERIDOS = { Manifest.permission.CAMERA }; // Permisos necesarios.

    private ImageCapture capturaImagen; // Objeto de CameraX para capturar fotos.
    private File directorioSalida; // Directorio donde se guardan las fotos temporalmente.
    private ExecutorService ejecutorCamara; // Hilo en segundo plano para operaciones de camara.
    private String tokenAuth; // Token de autenticacion JWT.
    private ServicioApi servicioApi; // Interfaz de la API (Retrofit).

    private android.widget.EditText etNombreObjetivo; // Campo de texto para el nombre del objetivo.
    private CameraSelector selectorCamara = CameraSelector.DEFAULT_BACK_CAMERA; // Selector de camara (trasera por
                                                                                // defecto).

    // Launcher para abrir la galeria y recibir la imagen seleccionada.
    private final ActivityResultLauncher<String> lanzadorGaleria = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                // Cuando el usuario selecciona una imagen, la procesamos.
                if (uri != null) {
                    procesarImagenGaleria(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera); // Carga el layout de la pantalla de camara.

        // Vincula los elementos de la interfaz con el codigo.
        etNombreObjetivo = findViewById(R.id.etTargetName);
        // Recibe el token de autenticacion desde la actividad anterior (MainActivity).
        tokenAuth = getIntent().getStringExtra("authToken");
        // Obtiene la instancia de la API configurada.
        servicioApi = ClienteRetrofit.obtenerServicioApi(this);

        // Comprueba si ya tenemos permiso de camara.
        if (todosPermisosOtorgados()) {
            iniciarCamara(); // Si hay permiso, inicia la camara directamente.
        } else {
            // Si no, solicita el permiso al usuario.
            ActivityCompat.requestPermissions(this, PERMISOS_REQUERIDOS, CODIGO_SOLICITUD_PERMISOS);
        }

        // Boton para capturar foto con la camara.
        Button botonCaptura = findViewById(R.id.capture_button);
        botonCaptura.setOnClickListener(v -> tomarFoto());

        // Boton para seleccionar imagen de la galeria.
        findViewById(R.id.btnGallery).setOnClickListener(v -> {
            // Valida que se haya introducido un nombre antes de abrir la galeria.
            String nombreObjetivo = etNombreObjetivo.getText().toString().trim();
            if (nombreObjetivo.isEmpty()) {
                Toast.makeText(this, "Introduce el nombre primero", Toast.LENGTH_SHORT).show();
                return;
            }
            lanzadorGaleria.launch("image/*"); // Abre la galeria mostrando solo imagenes.
        });

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

        // Configura el directorio de salida y el executor de la camara.
        directorioSalida = obtenerDirectorioSalida();
        ejecutorCamara = Executors.newSingleThreadExecutor(); // Un solo hilo para procesar imagenes.
    }

    // Procesa una imagen seleccionada desde la galeria.
    private void procesarImagenGaleria(Uri uri) {
        String nombreObjetivo = etNombreObjetivo.getText().toString().trim();
        // Se ejecuta en un hilo secundario para no bloquear la interfaz.
        ejecutorCamara.execute(() -> {
            try {
                // Abre un flujo de lectura (InputStream) desde la URI de la imagen.
                InputStream flujoEntrada = getContentResolver().openInputStream(uri);
                // Decodifica el flujo en un Bitmap (imagen en memoria).
                Bitmap bitmap = BitmapFactory.decodeStream(flujoEntrada);

                // Comprime la imagen a JPEG con calidad del 70%.
                ByteArrayOutputStream flujoSalida = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, flujoSalida);
                byte[] arrayBytes = flujoSalida.toByteArray();
                // Convierte los bytes de la imagen a texto Base64 para enviarla por red.
                String imagenBase64 = Base64.encodeToString(arrayBytes, Base64.DEFAULT);

                // Crea el objeto Objetivo con nombre, imagen y prioridad.
                ServicioApi.Objetivo objetivo = new ServicioApi.Objetivo(nombreObjetivo, imagenBase64, "high");
                // Envia la peticion asíncrona al servidor para crear el objetivo.
                servicioApi.crearObjetivo(tokenAuth, objetivo).enqueue(new Callback<ServicioApi.Objetivo>() {
                    @Override
                    public void onResponse(Call<ServicioApi.Objetivo> call, Response<ServicioApi.Objetivo> response) {
                        if (response.isSuccessful()) {
                            Log.d(TAG, "Objetivo de galeria subido correctamente");
                            runOnUiThread(() -> {
                                Toast.makeText(CamaraActivity.this, "Objetivo registrado", Toast.LENGTH_SHORT).show();
                                finish(); // Cierra esta actividad y vuelve a la principal.
                            });
                        } else {
                            Log.e(TAG, "Fallo en la subida: " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(Call<ServicioApi.Objetivo> call, Throwable t) {
                        Log.e(TAG, "Error de subida: " + t.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error procesando imagen de galeria", e);
            }
        });
    }

    // Captura una foto con la camara del dispositivo.
    private void tomarFoto() {
        if (capturaImagen == null)
            return;

        // Valida que se haya introducido un nombre.
        String nombreObjetivo = etNombreObjetivo.getText().toString().trim();
        if (nombreObjetivo.isEmpty()) {
            Toast.makeText(this, "Por favor, introduce el nombre del objetivo", Toast.LENGTH_SHORT).show();
            return;
        }

        // Crea un archivo temporal con nombre aleatorio (UUID) para guardar la foto.
        File archivoFoto = new File(directorioSalida, UUID.randomUUID().toString() + ".jpg");
        ImageCapture.OutputFileOptions opcionesSalida = new ImageCapture.OutputFileOptions.Builder(archivoFoto).build();

        // Ejecuta la captura de imagen.
        capturaImagen.takePicture(opcionesSalida, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults resultadoArchivo) {
                        // Si la foto se guardó correctamente, la sube al servidor.
                        Toast.makeText(CamaraActivity.this, "Foto capturada. Subiendo...", Toast.LENGTH_SHORT).show();
                        subirFoto(archivoFoto, nombreObjetivo);
                        finish(); // Vuelve a MainActivity inmediatamente.
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException excepcion) {
                        Log.e(TAG, "Fallo en la captura: " + excepcion.getMessage(), excepcion);
                    }
                });
    }

    // Sube la foto capturada al servidor como un nuevo objetivo.
    private void subirFoto(File archivoFoto, String nombre) {
        ejecutorCamara.execute(() -> {
            try {
                // Decodifica el archivo de foto a un Bitmap.
                Bitmap bitmap = BitmapFactory.decodeFile(archivoFoto.getAbsolutePath());
                // Comprime a JPEG al 70% y convierte a Base64.
                ByteArrayOutputStream flujoSalida = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, flujoSalida);
                byte[] arrayBytes = flujoSalida.toByteArray();
                String imagenBase64 = Base64.encodeToString(arrayBytes, Base64.DEFAULT);

                // Construye el objeto Objetivo y lo envia al servidor.
                ServicioApi.Objetivo objetivo = new ServicioApi.Objetivo(nombre, imagenBase64, "high");

                servicioApi.crearObjetivo(tokenAuth, objetivo).enqueue(new Callback<ServicioApi.Objetivo>() {
                    @Override
                    public void onResponse(Call<ServicioApi.Objetivo> call, Response<ServicioApi.Objetivo> response) {
                        if (response.isSuccessful()) {
                            Log.d(TAG, "Objetivo subido correctamente");
                        } else {
                            Log.e(TAG, "Fallo en la subida: " + response.code());
                        }
                    }

                    @Override
                    public void onFailure(Call<ServicioApi.Objetivo> call, Throwable t) {
                        Log.e(TAG, "Error de subida: " + t.getMessage());
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error procesando imagen para subir", e);
            }
        });
    }

    // Inicializa la camara usando la API CameraX de Android.
    private void iniciarCamara() {
        // Obtiene un Future (promesa) del proveedor de camara.
        ListenableFuture<ProcessCameraProvider> futuroProveedorCamara = ProcessCameraProvider.getInstance(this);

        // Cuando el proveedor esté listo, configura la camara.
        futuroProveedorCamara.addListener(() -> {
            try {
                ProcessCameraProvider proveedorCamara = futuroProveedorCamara.get();

                // Configura la vista previa (lo que ve el usuario en pantalla).
                Preview vistaPrevia = new Preview.Builder().build();
                vistaPrevia.setSurfaceProvider(((PreviewView) findViewById(R.id.viewFinder)).getSurfaceProvider());

                // Configura el caso de uso de captura de imagen.
                capturaImagen = new ImageCapture.Builder().build();

                // Selecciona la camara (trasera o delantera segun el estado).
                // selectorCamara ya esta definido como miembro de la clase.

                // Desvincula cualquier uso anterior y vincula los nuevos casos de uso.
                proveedorCamara.unbindAll();
                proveedorCamara.bindToLifecycle(this, selectorCamara, vistaPrevia, capturaImagen);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error al vincular casos de uso de la camara", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // Verifica si todos los permisos requeridos han sido concedidos.
    private boolean todosPermisosOtorgados() {
        for (String permiso : PERMISOS_REQUERIDOS) {
            if (ContextCompat.checkSelfPermission(this, permiso) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // Callback que se ejecuta cuando el usuario responde a la solicitud de
    // permisos.
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CODIGO_SOLICITUD_PERMISOS) {
            if (todosPermisosOtorgados()) {
                iniciarCamara(); // Si se concedieron, inicia la camara.
            } else {
                Toast.makeText(this, "Permisos no concedidos por el usuario.", Toast.LENGTH_SHORT).show();
                finish(); // Si no, cierra la actividad.
            }
        }
    }

    // Obtiene el directorio de salida para guardar las fotos capturadas.
    private File obtenerDirectorioSalida() {
        File[] directoriosMedia = getExternalMediaDirs();
        if (directoriosMedia != null && directoriosMedia.length > 0) {
            // Crea una subcarpeta con el nombre de la app en el directorio multimedia
            // externo.
            File dirMedia = new File(directoriosMedia[0], getString(R.string.app_name));
            if (dirMedia.exists() || dirMedia.mkdirs())
                return dirMedia;
        }
        // Alternativa: usa el directorio interno de archivos de la app.
        return getFilesDir();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Apaga el executor de la camara para liberar recursos.
        ejecutorCamara.shutdown();
    }
}
