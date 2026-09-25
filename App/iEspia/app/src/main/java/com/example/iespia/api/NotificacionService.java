package com.example.iespia.api;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.content.pm.ServiceInfo;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.iespia.MainActivity;
import com.example.iespia.R;

// Servicio en primer plano que mantiene activa la conexion
// Socket.IO incluso cuando la app está en segundo plano.
// Esto permite recibir notificaciones en tiempo real de reconocimiento facial
// sin que Android cierre el proceso por inactividad.
public class NotificacionService extends Service {
    private static final String TAG = "ServicioNotificacion"; // Etiqueta para logs.
    private static final String ID_CANAL = "iEspiaNotifications"; // ID del canal de notificaciones.
    private static final int ID_NOTIFICACION = 1; // ID de la notificacion persistente del servicio.
    private static final int ID_NOTIFICACION_ALERTA = 2; // ID para las alertas de reconocimiento.

    private GestorSocketIO gestorSocketIO; // Gestor de la conexion WebSocket.
    private String idAgenteActual; // ID del agente actualmente conectado.

    // onStartCommand se ejecuta cada vez que se inicia o reinicia el servicio.
    // Recibe el agentId como extra del Intent para identificar al dispositivo.
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String idAgente = intent.getStringExtra("agentId");
        Log.d(TAG, "Servicio iniciando para agente: " + idAgente);

        // Crea el canal de notificaciones (requerido en Android 8+).
        crearCanalNotificaciones();

        // Inicia el servicio como Foreground Service.
        // En Android 10+ (Q), se especifica el tipo de servicio.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_NOTIFICACION, crearNotificacionPrimerPlano(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_NOTIFICACION, crearNotificacionPrimerPlano());
        }

        // Configura el socket solo si el agentId es valido y diferente al actual.
        // Esto evita reconexiones innecesarias si el servicio se reinicia con el mismo
        // agente.
        if (idAgente != null) {
            if (gestorSocketIO == null || !idAgente.equals(idAgenteActual)) {
                // Si ya habia un socket anterior, lo desconecta primero.
                if (gestorSocketIO != null) {
                    gestorSocketIO.desconectar();
                }
                idAgenteActual = idAgente;
                configurarSocket(idAgente); // Establece la nueva conexion.
            }
        }

        // START_STICKY: si el sistema mata el servicio por falta de memoria,
        // lo reiniciara automaticamente cuando haya recursos disponibles.
        return START_STICKY;
    }

    // Configura la conexion Socket.IO e implementa los callbacks del listener.
    private void configurarSocket(String idAgente) {
        gestorSocketIO = new GestorSocketIO(idAgente, new GestorSocketIO.EscuchaMensajes() {
            @Override
            public void alRecibirMensaje(String mensaje) {
                // Cuando llega un mensaje del servidor (ej: objetivo identificado),
                // muestra una notificacion del sistema al usuario.
                mostrarNotificacionSistema(mensaje);
            }

            @Override
            public void alConectar() {
                Log.d(TAG, "Socket conectado en segundo plano");
            }

            @Override
            public void alDesconectar() {
                Log.d(TAG, "Socket desconectado en segundo plano");
            }

            @Override
            public void alError(String error) {
                Log.e(TAG, "Error de socket en segundo plano: " + error);
            }
        });
        gestorSocketIO.conectar(); // Inicia la conexion al servidor.
    }

    // Muestra una notificacion del sistema con alta prioridad (alerta).
    // Esta notificacion aparece incluso con la app cerrada, ya que el servicio
    // sigue ejecutandose en primer plano.
    private void mostrarNotificacionSistema(String mensaje) {
        NotificationManager gestorNotificaciones = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Al pulsar la notificacion, abre la MainActivity.
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent intentPendiente = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        // Construye la notificacion con prioridad alta y categoria de alarma.
        Notification notificacion = new NotificationCompat.Builder(this, ID_CANAL)
                .setSmallIcon(R.drawable.baseline_radio_button_checked_24) // Icono pequeño.
                .setContentTitle("iEspia ALERTA") // Titulo de la alerta.
                .setContentText(mensaje) // Texto corto.
                .setStyle(new NotificationCompat.BigTextStyle().bigText(mensaje)) // Texto expandible.
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Prioridad alta.
                .setCategory(NotificationCompat.CATEGORY_ALARM) // Categoria de alarma.
                .setAutoCancel(true) // Se cierra al pulsarla.
                .setContentIntent(intentPendiente) // Accion al pulsar.
                .build();

        // Muestra la notificacion con el ID de alerta (diferente al del servicio).
        gestorNotificaciones.notify(ID_NOTIFICACION_ALERTA, notificacion);
    }

    // Crea la notificacion persistente del servicio en primer plano.
    // Esta notificacion es obligatoria para los Foreground Services
    // y aparece mientras el servicio está activo.
    private Notification crearNotificacionPrimerPlano() {
        return new NotificationCompat.Builder(this, ID_CANAL)
                .setContentTitle("iEspia Activo") // Titulo informativo.
                .setContentText("Monitorizando objetivos...") // Descripcion.
                .setSmallIcon(R.drawable.baseline_radio_button_checked_24) // Icono.
                .setPriority(NotificationCompat.PRIORITY_LOW) // Prioridad baja (discreta).
                .build();
    }

    // Crea el canal de notificaciones (requerido en Android 8.0 Oreo y superiores).
    // Sin un canal, las notificaciones no se mostrarian en versiones modernas de
    // Android.
    private void crearCanalNotificaciones() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel canalServicio = new NotificationChannel(
                    ID_CANAL, // Identificador del canal.
                    "iEspia Alertas de Reconocimiento", // Nombre visible para el usuario.
                    NotificationManager.IMPORTANCE_HIGH); // Importancia alta (sonido y popup).
            NotificationManager gestor = getSystemService(NotificationManager.class);
            if (gestor != null) {
                gestor.createNotificationChannel(canalServicio);
            }
        }
    }

    // Se ejecuta cuando el servicio es destruido (ej: usuario detiene la app
    // manualmente).
    @Override
    public void onDestroy() {
        super.onDestroy();
        // Desconecta el socket para liberar recursos de red.
        if (gestorSocketIO != null) {
            gestorSocketIO.desconectar();
        }
        Log.d(TAG, "Servicio destruido");
    }

    // onBind devuelve null porque este es un servicio "started" (no "bound").
    // No se vincula a ninguna actividad directamente.
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
