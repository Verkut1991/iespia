package com.example.iespia.api;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;
import java.net.URISyntaxException;

// Gestor de conexion WebSocket usando la librería Socket.IO.
// Permite recibir notificaciones en tiempo real del servidor (por ejemplo,
// cuando un objetivo es identificado mediante reconocimiento facial).
public class GestorSocketIO {
    private static final String TAG = "GestorSocketIO"; // Etiqueta para logs de depuracion.
    // URL del servidor Socket.IO al que se conecta la app.
    private static final String URL_SOCKET = "http://10.0.2.2:6010"; // emulador Android (10.0.2.2 = host machine)

    private Socket socket; // Instancia del socket de comunicacion.
    private final String idAgente; // Identificador unico del agente (dispositivo).
    private final EscuchaMensajes escuchaMensajes; // Callback para notificar a la UI.

    // Interfaz de escucha (listener) que debe implementar quien use esta clase.
    // Define los metodos que se ejecutan al recibir un mensaje, conectarse,
    // desconectarse, o producirse un error.
    public interface EscuchaMensajes {
        void alRecibirMensaje(String mensaje); // Se ejecuta al recibir una notificacion del servidor.

        void alConectar(); // Se ejecuta cuando la conexion se establece.

        void alDesconectar(); // Se ejecuta cuando se pierde la conexion.

        void alError(String error); // Se ejecuta si ocurre un error de conexion.
    }

    // Constructor: recibe el ID del agente y el listener de mensajes.
    // Configura las opciones de reconexion e inicializa el socket.
    public GestorSocketIO(String idAgente, EscuchaMensajes escuchaMensajes) {
        this.idAgente = idAgente;
        this.escuchaMensajes = escuchaMensajes;

        try {
            // Configurar opciones de Socket.IO
            IO.Options opciones = new IO.Options();
            opciones.reconnection = true; // Reconexion automatica activada.
            opciones.reconnectionAttempts = Integer.MAX_VALUE; // Intentos de reconexion ilimitados.
            opciones.reconnectionDelay = 1000; // Espera 1 segundo entre reintentos.
            opciones.reconnectionDelayMax = 5000; // Maximo 5 segundos entre reintentos.
            opciones.timeout = 20000; // Timeout de conexion: 20 segundos.

            // Crea la instancia del socket con la URL y las opciones.
            socket = IO.socket(URL_SOCKET, opciones);
            // Registra los listeners para los distintos eventos del socket.
            configurarEscuchas();
        } catch (URISyntaxException e) {
            // Si la URL es invalida, registra el error.
            Log.e(TAG, "Error creando socket: " + e.getMessage());
            if (escuchaMensajes != null) {
                escuchaMensajes.alError("Error creando socket: " + e.getMessage());
            }
        }
    }

    // Configura los listeners (escuchadores) para todos los eventos del socket.
    private void configurarEscuchas() {
        // Evento de conexion exitosa
        // Se dispara cuando el socket se conecta al servidor.
        socket.on(Socket.EVENT_CONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d(TAG, "Conectado al servidor Socket.IO");

                // Una vez conectado, registra este agente con el servidor
                // enviando el evento "register" con el agentId.
                // Esto permite al servidor saber qué dispositivo está conectado
                // para enviarle las notificaciones correspondientes.
                try {
                    JSONObject datos = new JSONObject();
                    datos.put("agentId", idAgente);
                    socket.emit("register", datos);
                } catch (JSONException e) {
                    Log.e(TAG, "Error creando JSON de registro: " + e.getMessage());
                }
            }
        });

        // Evento de desconexion
        // Se dispara cuando el socket pierde la conexion con el servidor.
        socket.on(Socket.EVENT_DISCONNECT, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                Log.d(TAG, "Desconectado del servidor Socket.IO");
                if (escuchaMensajes != null) {
                    escuchaMensajes.alDesconectar();
                }
            }
        });

        // Evento de error de conexion
        // Se dispara si hay un error al intentar conectar.
        socket.on(Socket.EVENT_CONNECT_ERROR, new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                String error = args.length > 0 ? args[0].toString() : "Error desconocido";
                Log.e(TAG, "Error de conexión: " + error);
                if (escuchaMensajes != null) {
                    escuchaMensajes.alError(error);
                }
            }
        });

        // Evento personalizado: "connected" (bienvenida del servidor)
        // El servidor emite este evento al confirmar la conexion del agente.
        socket.on("connected", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                if (args.length > 0) {
                    Log.d(TAG, "Bienvenida del servidor: " + args[0].toString());
                }
                if (escuchaMensajes != null) {
                    escuchaMensajes.alConectar();
                }
            }
        });

        // Evento personalizado: "notification"
        // Este es el evento principal: el servidor notifica cuando se identifica un
        // objetivo.
        // Los tipos de notificacion son:
        // - TARGET_IDENTIFIED: un solo objetivo reconocido.
        // - MULTIPLE_TARGETS_DETECTED: varios objetivos detectados en la misma imagen.
        socket.on("notification", new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                if (args.length > 0) {
                    Log.d(TAG, "Notificación recibida: " + args[0]);
                    try {
                        // Parsea el JSON recibido del servidor.
                        JSONObject datos = (JSONObject) args[0];
                        String tipo = datos.optString("type"); // Tipo de notificacion.

                        // Comprueba si es una notificacion de identificacion.
                        if ("TARGET_IDENTIFIED".equals(tipo) || "MULTIPLE_TARGETS_DETECTED".equals(tipo)) {
                            // Extrae los datos del objetivo identificado.
                            JSONObject objetivo = datos.optJSONObject("target");
                            String nombre = objetivo != null ? objetivo.optString("name") : "Desconocido";
                            String confianza = datos.optString("confidence_score", "0");

                            // Construye el mensaje legible para el usuario.
                            StringBuilder constructorMensaje = new StringBuilder();
                            if ("MULTIPLE_TARGETS_DETECTED".equals(tipo)) {
                                constructorMensaje.append("¡MÚLTIPLES OBJETIVOS!\n");
                            } else {
                                constructorMensaje.append("¡OBJETIVO IDENTIFICADO!\n");
                            }

                            constructorMensaje.append("Nombre: ").append(nombre)
                                    .append("\nSimilitud: ").append(confianza).append("%");

                            // Si hay datos de geolocalizacion, los añade al mensaje.
                            if (datos.has("geolocation")) {
                                JSONObject geo = datos.optJSONObject("geolocation");
                                if (geo != null) {
                                    constructorMensaje.append("\nUbicación: ").append(geo.optDouble("lat")).append(", ")
                                            .append(geo.optDouble("lng"));
                                }
                            }

                            // Envia el mensaje formateado al listener (que lo mostrara como notificacion).
                            final String mensajeFinal = constructorMensaje.toString();
                            Log.i(TAG, mensajeFinal);

                            if (escuchaMensajes != null) {
                                escuchaMensajes.alRecibirMensaje(mensajeFinal);
                            }
                        }
                    } catch (Exception e) {
                        // Si falla el parseo del JSON, envia el texto crudo.
                        Log.e(TAG, "Error parseando notificación", e);
                        if (escuchaMensajes != null) {
                            escuchaMensajes.alRecibirMensaje(args[0].toString());
                        }
                    }
                }
            }
        });
    }

    // Conectar al servidor Socket.IO.
    // Solo conecta si el socket existe y no esta ya conectado.
    public void conectar() {
        if (socket != null && !socket.connected()) {
            socket.connect();
            Log.d(TAG, "Conectando al servidor Socket.IO...");
        }
    }

    // Desconectar del servidor Socket.IO.
    public void desconectar() {
        if (socket != null) {
            socket.disconnect();
            Log.d(TAG, "Desconectado del servidor Socket.IO");
        }
    }

    // Enviar un mensaje (evento) al servidor.
    // Solo envia si el socket esta conectado.
    public void enviarMensaje(String evento, JSONObject datos) {
        if (socket != null && socket.connected()) {
            socket.emit(evento, datos);
            Log.d(TAG, "Mensaje enviado: " + evento + " -> " + datos.toString());
        } else {
            Log.w(TAG, "No se puede enviar mensaje, socket no conectado");
        }
    }

    // Verificar si el socket esta conectado al servidor.
    public boolean estaConectado() {
        return socket != null && socket.connected();
    }
}
