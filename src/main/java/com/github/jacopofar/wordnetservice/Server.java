package com.github.jacopofar.wordnetservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jacopofar.wordnetservice.messages.Annotation;
import com.github.jacopofar.wordnetservice.messages.AnnotationRequest;
import net.sf.extjwnl.JWNLException;
import net.sf.extjwnl.data.IndexWord;
import net.sf.extjwnl.data.POS;
import net.sf.extjwnl.data.PointerUtils;
import net.sf.extjwnl.data.list.PointerTargetNodeList;
import net.sf.extjwnl.dictionary.Dictionary;
import spark.Response;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static spark.Spark.*;

public class Server {
    static Dictionary dictionary;
    public static void main(String[] args) throws JWNLException {

        dictionary = Dictionary.getDefaultResourceInstance();


        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
        port(5678);
        //show exceptions in console and HTTP responses
        exception(Exception.class, (exception, request, response) -> {
            //show the exceptions using stdout
            System.out.println("Exception:");
            exception.printStackTrace(System.out);
            response.status(400);
            response.body(exception.getMessage());
        });

        post("/hypernims_tagger", (request, response) -> {
            ObjectMapper mapper = new ObjectMapper();
            AnnotationRequest ar = mapper.readValue(request.body(), AnnotationRequest.class);
            if(ar.errorMessages().size() != 0){
                response.status(400);
                return "invalid request body. Errors: " + ar.errorMessages() ;
            }
            HashSet<String> accepted = new HashSet<>(5);
            //the user can provide a word with no POS (all the matches will be used) or a POS and the word
            String[] params = ar.getParameter().split(" ");
            if(params.length == 2){
                String wordType = params[0];
                if(!wordType.matches("(adjective)|(noun)|(verb)|a|n|v")){
                    response.status(400);
                    return "invalid request, the word type has to be adjective, noun or verb, or a, n, v. This was " + wordType ;
                }
                IndexWord w = null;
                if(wordType.startsWith("a"))
                    w = dictionary.lookupIndexWord(POS.ADJECTIVE, params[1]);
                if(wordType.startsWith("v"))
                    w = dictionary.lookupIndexWord(POS.VERB, params[1]);
                if(wordType.startsWith("n"))
                    w = dictionary.lookupIndexWord(POS.NOUN, params[1]);
                if(w == null){
                    System.out.println(" UNKNWOWN WORD: " + params[1]);
                    response.type("application/json; charset=utf-8");
                    return "{\"annotations\":[]}";
                }
                PointerTargetNodeList hypernyms = PointerUtils.getDirectHypernyms(w.getSenses().get(0));
                accepted.addAll(hypernyms.get(0).getSynset().getWords().stream().map(sw -> sw.getLemma()).collect(Collectors.toList()));
            }
            if(params.length == 1){
                for(POS p:POS.getAllPOS()){
                    IndexWord w = dictionary.lookupIndexWord(p,  params[0]);
                    if(w == null)
                        continue;
                    accepted.addAll(PointerUtils.getDirectHypernyms(w.getSenses().get(0))
                            .get(0).getSynset().getWords().stream().map(sw -> sw.getLemma()).collect(Collectors.toList()));
                }
            }

            if(params.length != 1 && params.length != 2){
                response.status(400);
                return "invalid parameter. Must be in the form POS+word (e.g. 'n cat') or just a word (e.g. 'cat)" ;
            }

            List<Annotation> anns = new ArrayList<>();

            String text = ar.getText().toLowerCase();
            accepted.stream().forEach(l -> {
                if(text.equals(l)){
                    anns.add(new Annotation(0, text.length()));
                    return;
                }
                int lastIndex=0;
                while(true){
                    int ind=text.indexOf(l, lastIndex);
                    if(ind ==- 1)
                        break;
                    anns.add(new Annotation(ind, ind + l.length()));
                    lastIndex=ind+l.length();
                }
            });
            return sendAnnotations(anns, response);
        });


        /**
         * List the hypernims of a given word
         * */
        get("/hypernims/:word", (request, response) -> {

            ArrayNode annotationArray =  JsonNodeFactory.instance.arrayNode();

            for(POS p:POS.getAllPOS()){
                IndexWord w = dictionary.lookupIndexWord(p,  request.params(":word"));
                if(w == null)
                    continue;
                PointerUtils.getDirectHypernyms(w.getSenses().get(0))
                        .get(0).getSynset().getWords().stream().map(sw -> sw.getLemma())
                        .forEach(l -> {
                            ObjectNode hyp = JsonNodeFactory.instance.objectNode();
                            hyp.put("POS", p.getLabel());
                            hyp.put("word", l);
                            annotationArray.add(hyp);
                        });
            }
            response.type("application/json; charset=utf-8");
            return annotationArray.toString();
        });
    }
    private static String sendAnnotations( List<Annotation> list, Response res){
        JsonNodeFactory nodeFactory = JsonNodeFactory.instance;
        ObjectNode retVal = nodeFactory.objectNode();
        ArrayNode annotationArray = retVal.putArray("annotations");

        for(Annotation ann:list){
            ObjectNode annotation = nodeFactory.objectNode();
            annotation.put("span_start", ann.getStart());
            annotation.put("span_end", ann.getEnd());
            if(ann.getAnnotation() != null){
                annotation.set("annotation", ann.getAnnotation());
            }
            annotationArray.add(annotation);
        }
        res.type("application/json; charset=utf-8");
        return retVal.toString();
    }
}