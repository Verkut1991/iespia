package com.example.iespia;

import android.os.Bundle;
import android.os.Build;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import com.example.iespia.api.ServicioApi;
import com.example.iespia.api.ClienteRetrofit;
import com.example.iespia.api.NotificacionService;
import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;

import android.content.SharedPreferences;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "iEspia"; // Etiqueta para logs.
    private static final String NOMBRE_PREFS = "iEspiaPrefs"; // Nombre del archivo SharedPreferences.
    private static final String CLAVE_TOKEN_AUTH = "authToken"; // Clave para almacenar el token JWT.
    private static final String CLAVE_ID_AGENTE = "agentId"; // Clave para almacenar el ID del agente.

    private ServicioApi servicioApi; // Interfaz de la API (Retrofit).
    private String tokenAuth; // Token JWT actual.
    private String idAgente; // ID unico del agente (dispositivo).

    // Elementos de la interfaz de usuario.
    private TextView tvRegistros; // Area de texto para mostrar logs.
    private Button btnLogin, btnEscanear, btnAnadirObjetivo, btnGaleria; // Botones de accion.

    // Launcher para seleccionar una imagen de la galeria.
    // Se registra al crear la actividad para recibir el resultado de forma segura.
    private final ActivityResultLauncher<String> lanzadorGaleria = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    procesarImagenGaleria(uri); // Procesa la imagen seleccionada.
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Carga el layout principal.

        // Vincula los elementos del layout con las variables Java.
        tvRegistros = findViewById(R.id.tvLogs);
        btnLogin = findViewById(R.id.btnLogin);
        btnEscanear = findViewById(R.id.btnScan);
        btnGaleria = findViewById(R.id.btnGallery);
        btnAnadirObjetivo = findViewById(R.id.btnAddTarget);

        // Obtiene la instancia configurada de la API.
        servicioApi = ClienteRetrofit.obtenerServicioApi(this);

        // Intenta cargar credenciales guardadas de una sesion anterior.
        cargarCredenciales();

        // Boton de autenticacion: pulsacion corta genera una nueva API Key.
        btnLogin.setOnClickListener(v -> realizarKeygen());
        // Pulsacion larga: resetea las credenciales (cierra sesion).
        btnLogin.setOnLongClickListener(v -> {
            reiniciarCredenciales();
            return true; // Indica que el evento fue consumido.
        });

        // Boton de escaneo: abre la actividad ScanActivity.
        btnEscanear.setOnClickListener(v -> realizarEscaneo());
        // Boton de galeria: abre el selector de imagenes para escanear.
        btnGaleria.setOnClickListener(v -> lanzadorGaleria.launch("image/*"));
        // Boton de añadir objetivo: abre CamaraActivity.
        btnAnadirObjetivo.setOnClickListener(v -> crearObjetivo());

        // Si ya hay un token guardado, restaura la sesion automaticamente.
        if (tokenAuth != null) {
            registrar("Sesión restaurada. ID de Agente: " + idAgente);
            actualizarUIAutenticado(); // Activa los botones de accion.
            conectarWebSocket(); // Inicia el servicio de notificaciones.
        } else {
            // Si no hay sesion, genera un ID de agente temporal.
            idAgente = UUID.randomUUID().toString();
            registrar("Listo para inicializar. ID de Agente temporal: " + idAgente);
        }
    }

    // Guarda el token y el agentId en SharedPreferences para persistir la sesion.
    private void guardarCredenciales() {
        SharedPreferences preferencias = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE);
        preferencias.edit()
                .putString(CLAVE_TOKEN_AUTH, tokenAuth)
                .putString(CLAVE_ID_AGENTE, idAgente)
                .apply(); // apply() es asincrono (no bloquea el hilo principal).
    }

    // Carga el token y el agentId desde SharedPreferences.
    private void cargarCredenciales() {
        SharedPreferences preferencias = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE);
        tokenAuth = preferencias.getString(CLAVE_TOKEN_AUTH, null);
        idAgente = preferencias.getString(CLAVE_ID_AGENTE, null);
    }

    // Resetea las credenciales: cierra sesion, detiene el servicio y limpia
    // SharedPreferences.
    private void reiniciarCredenciales() {
        // Detiene el servicio de notificaciones en segundo plano.
        stopService(new Intent(this, NotificacionService.class));
        // Borra todas las preferencias guardadas.
        SharedPreferences preferencias = getSharedPreferences(NOMBRE_PREFS, MODE_PRIVATE);
        preferencias.edit().clear().apply();
        tokenAuth = null;
        idAgente = UUID.randomUUID().toString(); // Genera un nuevo ID temporal.

        // Actualiza la interfaz: desactiva botones de accion y reactiva el de login.
        runOnUiThread(() -> {
            btnEscanear.setEnabled(false);
            btnGaleria.setEnabled(false);
            btnAnadirObjetivo.setEnabled(false);
            btnLogin.setEnabled(true);
            btnLogin.setText("Autenticar Agente");
            registrar("Credenciales reiniciadas. Nuevo ID de Agente temporal: " + idAgente);
        });
    }

    // Actualiza la interfaz cuando el agente está autenticado.
    // Activa los botones de escaneo/galeria/objetivo y desactiva el de login.
    private void actualizarUIAutenticado() {
        runOnUiThread(() -> {
            btnEscanear.setEnabled(true);
            btnGaleria.setEnabled(true);
            btnAnadirObjetivo.setEnabled(true);
            btnLogin.setEnabled(false);
            btnLogin.setText("Autenticado");
        });
    }

    // Solicita una nueva API Key al servidor.
    // Envia el agentId actual y recibe un token JWT que se usa para autenticar
    // todas las peticiones posteriores a la API.
    private void realizarKeygen() {
        registrar("Intentando recolección automática de clave...");
        ServicioApi.PeticionLogin peticion = new ServicioApi.PeticionLogin(idAgente);
        servicioApi.generarClaveApi(peticion).enqueue(new Callback<ServicioApi.RespuestaKeygen>() {
            @Override
            public void onResponse(Call<ServicioApi.RespuestaKeygen> call,
                    Response<ServicioApi.RespuestaKeygen> response) {
                if (response.isSuccessful() && response.body() != null) {
                    // Almacena el token con el prefijo "Bearer" para las cabeceras HTTP.
                    tokenAuth = "Bearer " + response.body().apiKey;
                    // Actualiza el agentId por si el servidor asignó uno diferente.
                    idAgente = response.body().agentId;
                    registrar("¡API Key Generada! Agente: " + idAgente);

                    guardarCredenciales(); // Persiste las credenciales.
                    actualizarUIAutenticado(); // Actualiza la interfaz.
                    conectarWebSocket(); // Inicia las notificaciones en tiempo real.
                } else {
                    registrar("Fallo en Keygen: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<ServicioApi.RespuestaKeygen> call, Throwable t) {
                registrar("Error en Keygen: " + t.getMessage());
            }
        });
    }

    // Procesa una imagen seleccionada de la galeria para enviarla al escaneo.
    // Convierte la imagen a Base64 y la envia al endpoint de reconocimiento facial.
    private void procesarImagenGaleria(Uri uri) {
        try {
            // Lee la imagen como un flujo de bytes.
            InputStream flujoEntrada = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(flujoEntrada);

            // Comprime la imagen a JPEG al 70% y la codifica en Base64.
            ByteArrayOutputStream flujoSalida = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, flujoSalida);
            byte[] arrayBytes = flujoSalida.toByteArray();
            String imagenBase64 = Base64.encodeToString(arrayBytes, Base64.DEFAULT);

            registrar("Imagen seleccionada de galería. Enviando para escaneo...");
            // Crea la peticion de escaneo con la imagen codificada.
            ServicioApi.PeticionEscaneo peticion = new ServicioApi.PeticionEscaneo(imagenBase64);

            // Envia la peticion asíncrona al servidor.
            servicioApi.escanearRostro(tokenAuth, peticion).enqueue(new Callback<ServicioApi.RespuestaEscaneo>() {
                @Override
                public void onResponse(Call<ServicioApi.RespuestaEscaneo> call,
                        Response<ServicioApi.RespuestaEscaneo> response) {
                    if (response.isSuccessful()) {
                        registrar("Escaneo de Galería Iniciado. ID: " + response.body().scanId);
                    } else {
                        registrar("Escaneo Fallido: " + response.code());
                    }
                }

                @Override
                public void onFailure(Call<ServicioApi.RespuestaEscaneo> call, Throwable t) {
                    registrar("Error de Escaneo: " + t.getMessage());
                }
            });

        } catch (Exception e) {
            registrar("Error procesando imagen de galería: " + e.getMessage());
        }
    }

    // Inicia el servicio de notificaciones en segundo plano (NotificacionService).
    // Este servicio mantiene una conexion Socket.IO abierta para recibir alertas
    // de reconocimiento facial en tiempo real.
    private void conectarWebSocket() {
        // En Android 13+ (Tiramisu), se necesita permiso explicito para notificaciones.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.POST_NOTIFICATIONS }, 101);
            }
        }

        // Crea el Intent para iniciar el servicio con el agentId.
        Intent intentServicio = new Intent(this, NotificacionService.class);
        intentServicio.putExtra("agentId", idAgente);
        // En Android 8+ (Oreo), los servicios en primer plano deben iniciarse
        // con startForegroundService() en lugar de startService().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intentServicio);
        } else {
            startService(intentServicio);
        }
        registrar("Servicio de Notificaciones Iniciado para Agente: " + idAgente);
    }

    // Abre la actividad de escaneo (ScanActivity) pasando el token de
    // autenticacion.
    private void realizarEscaneo() {
        if (tokenAuth == null) {
            Toast.makeText(this, "Debe iniciar sesión primero", Toast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent intent = new android.content.Intent(this, ScanActivity.class);
        intent.putExtra("authToken", tokenAuth);
        startActivity(intent);
    }

    // Abre la actividad de camara (CamaraActivity) para registrar un nuevo
    // objetivo.
    private void crearObjetivo() {
        android.content.Intent intent = new android.content.Intent(this, CamaraActivity.class);
        intent.putExtra("authToken", tokenAuth);
        startActivity(intent);
    }

    // Metodo auxiliar para registrar mensajes tanto en el Logcat como en la
    // interfaz.
    private void registrar(String mensaje) {
        Log.d(TAG, mensaje);
        tvRegistros.append("\n" + mensaje); // Añade el mensaje al area de texto.
        // Scroll al final del texto (si esta dentro de un ScrollView).
        tvRegistros.post(() -> {
            // Logica de scroll si fuese necesario.
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // El servicio NotificacionService sigue ejecutandose en segundo plano
        // independientemente de que la actividad se destruya.
    }
}