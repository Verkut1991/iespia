package com.example.iespia.api;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

import java.util.List;

// Interfaz que define todos los endpoints de la API del servidor.
// Retrofit usará esta interfaz para generar el código de las peticiones HTTP automáticamente.
public interface ServicioApi {

    // Autenticación

    // Endpoint para iniciar sesión.
    // Envía un POST a /auth/login con el cuerpo PeticionLogin.
    @POST("auth/login")
    Call<RespuestaLogin> iniciarSesion(@Body PeticionLogin peticion);

    // Endpoint para renovar el token JWT.
    // Se envía el token antiguo en la cabecera 'Authorization'.
    @POST("auth/renew")
    Call<RespuestaLogin> renovarToken(@Header("Authorization") String token);

    // Endpoint para generar una nueva API Key de larga duración.
    @POST("auth/keygen")
    Call<RespuestaKeygen> generarClaveApi(@Body PeticionLogin peticion);

    // Gestión de Objetivos (Targets)

    // Obtiene la lista de todos los objetivos registrados.
    @GET("targets")
    Call<List<Objetivo>> obtenerObjetivos(@Header("Authorization") String token);

    // Crea un nuevo objetivo.
    @POST("targets")
    Call<Objetivo> crearObjetivo(@Header("Authorization") String token, @Body Objetivo objetivo);

    // Elimina un objetivo específico por su ID.
    @DELETE("targets/{id}")
    Call<ResponseBody> eliminarObjetivo(@Header("Authorization") String token, @Path("id") String id);

    // Reconocimiento Facial

    // Envía una imagen para ser escaneada y comparada con los objetivos.
    @POST("recognition/scan")
    Call<RespuestaEscaneo> escanearRostro(@Header("Authorization") String token, @Body PeticionEscaneo peticion);

    // DTOs (Data Transfer Objects)
    // Clases simples para mapear los JSON de petición y respuesta.

    // Objeto para enviar la petición de login (solo agentId).
    class PeticionLogin {
        String agentId;

        public PeticionLogin(String idAgente) {
            this.agentId = idAgente;
        }
    }

    // Objeto de respuesta del login (token y expiración).
    class RespuestaLogin {
        public String token;
        public String expiresIn;
    }

    // Objeto de respuesta al generar una API Key.
    class RespuestaKeygen {
        public String agentId;
        public String apiKey;
        public String issuedAt; // Fecha de emisión.
        public String expiresIn; // Fecha de expiración.
    }

    // Objeto que representa a un Objetivo (persona a vigilar).
    class Objetivo {
        public String id; // ID único en MongoDB.
        public String name; // Nombre del objetivo.
        public String image; // Imagen en Base64.
        public String priority; // Prioridad (Alta/Media/Baja).
        public boolean active; // Si está activo o no.

        public Objetivo(String nombre, String imagen, String prioridad) {
            this.name = nombre;
            this.image = imagen;
            this.priority = prioridad;
        }
    }

    // Objeto para la petición de escaneo.
    class PeticionEscaneo {
        public String image; // Imagen en Base64 para analizar.

        public PeticionEscaneo(String imagen) {
            this.image = imagen;
        }
    }

    // Objeto de respuesta del escaneo.
    class RespuestaEscaneo {
        public String scanId; // ID del escaneo registrado.
        public String status; // Resultado (match/no_match/error).
    }
}
